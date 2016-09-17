package so.jscinoz.wurmunlimited.mods.brandmod;

import java.lang.FunctionalInterface;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicInteger;

import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CannotCompileException;
import javassist.NotFoundException;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.BadBytecode;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import static java.util.logging.Level.INFO;
import static javassist.bytecode.Opcode.ALOAD;
import static javassist.bytecode.Opcode.IFNE;
import static javassist.bytecode.Opcode.LOOKUPSWITCH;
import static javassist.bytecode.Opcode.INVOKESTATIC;
import static javassist.bytecode.Opcode.INVOKEVIRTUAL;
import static javassist.bytecode.Opcode.SIPUSH;
import static so.jscinoz.wurmunlimited.mods.common.ModSupport.*;

public class BrandMod implements WurmServerMod, PreInitable {
  private static final String BRAND_CLASS_NAME =
    "com.wurmonline.server.creatures.Brand";

  private static final String MANAGE_MENU_CLASS_NAME =
    "com.wurmonline.server.behaviours.ManageMenu";

  private static final String CREATURE_CLASS_NAME =
    "com.wurmonline.server.creatures.Creature";

  private static final String CREATURES_CLASS_NAME =
    "com.wurmonline.server.creatures.Creatures";

  private static final String CREATURE_BEHAVIOUR_CLASS_NAME =
    "com.wurmonline.server.behaviours.CreatureBehaviour";

  private static final String COMMUNICATOR_CLASS_NAME =
    "com.wurmonline.server.creatures.Communicator";

  private static final String SERVERS_CLASS_NAME =
    "com.wurmonline.server.Servers";

  private static final String PVP_CHECK_METHOD_NAME = "isThisAPvpServer";

  private static final String BRAND_CHECK_METHOD_NAME = "isBranded";

  private static final int MANAGE_ANIMAL_ACTION = 663;

  // Default test for replacing a MethodCall with ExprEditor - simply checks
  // that the target MethodCall is for Servers.isThisAPvpServer
  private static final Predicate<MethodCall> DEFAULT_PREDICATE = m -> {
    return m.getMethodName().equals(PVP_CHECK_METHOD_NAME) &&
           m.getClassName().equals(SERVERS_CLASS_NAME);
  };

  // Default lookahead predicate used when the predicates List contains null
  // values
  private static final LookaheadPredicate DEFAULT_LOOKAHEAD_PREDICATE =
    (op, method) -> true;

  private static final Logger logger =
    Logger.getLogger(BrandMod.class.getName());

  // Finds the first method with the given name on the given class. Method
  // parameters are ignored.
  private CtMethod findMatchingMethod(CtClass targetClass, String methodName)
      throws NotFoundException {
    for (CtMethod m : targetClass.getDeclaredMethods()) {
      if (m.getName().equals(methodName)) {
        return m;
      }
    }

    throw new NotFoundException(String.format(
      "Could not find method %s on %s", methodName, targetClass.getName()));
  }

  private List<Integer> searchForInstructions(CtMethod method, Searcher searcher)
      throws NotFoundException, BadBytecode {
    MethodInfo mi = method.getMethodInfo();
    ConstPool cp = mi.getConstPool();
    CodeAttribute ca = mi.getCodeAttribute();
    CodeIterator ci = ca.iterator();

    List<Integer> result = new ArrayList<>();

    while (ci.hasNext()) {
      try {
        int targetPos = searcher.search(ci, cp);

        result.add(targetPos);

      } catch (NotFoundException e) {
        break;
      }
    }

    if (result.size() == 0) {
      throw new NotFoundException("No matching instructions found");
    }

    return result;
  }

  private int searchForInstruction(CtMethod method, Searcher searcher)
      throws NotFoundException, BadBytecode {
    return searchForInstructions(method, searcher).get(0);
  }

  // Returns the index of the first byte of the first instruction within a
  // sequence of instructions that matches the given List of predicates. Will
  // throw if not present
  private int findSequence(
      CodeIterator ci, ConstPool cp,
      List<LookaheadPredicate> predicates)
      throws NotFoundException, BadBytecode {
    int pos;

    final int maxIndex = predicates.size() - 1;
    int testIndex = 0;

    int seqStart = -1;

    while (ci.hasNext()) {
      pos = ci.next();
      int op = ci.byteAt(pos);

      // Lookup the method name if we have it. We don't care about
      // INVOKEDYNAMIC/INVOKEINTERFACE/INVOKESPECIAL
      String methodName = (op == INVOKESTATIC || op == INVOKEVIRTUAL)
        ? cp.getMethodrefName(ci.s16bitAt(pos + 1))
        : null;

      LookaheadPredicate p = predicates.get(testIndex);

      if (p == null) {
        // Treat nulls in the predicate list as wildcards - i.e. match any
        // instruction at this position in the sequence
        p =  DEFAULT_LOOKAHEAD_PREDICATE;
      }

      if (p.test(op, methodName)) {
        if (seqStart == -1 || testIndex == 0) {
          // We've just matched the first predicate in the list, so record the
          // bytecode index for the start of the seqeuence in case this turns
          // out to be what we're looking for.
          seqStart = pos;
        }

        if (testIndex == maxIndex) {
          // Just passed the last test - we've found the first instance of the
          // target sequence - return its start index.
          return seqStart;
        }

        // The sequence matches so far, but we still have more predicates to
        // test
        testIndex++;
      } else {
        // Predicate test failed, start again with the first predicate for the
        // next instruction.
        testIndex = 0;
      }
    }

    throw new NotFoundException("No matching bytecode sequence found");
  }

  // Replaces all Servers.isThisAPvpServer MethodCalls that match the given
  // predicate with a literal false, in the body of the given method. Will throw
  // if the replacement count is not equal to the expected patch count, so we
  // don't silently continue if the patching didn't go exactly as intended.
  private void stripPvpCheck(
      ClassPool pool, CtMethod method,
      int expectedPatches, final Predicate<MethodCall> p)
      throws NotFoundException, BadBytecode, CannotCompileException {
    String fqMethodName = String.format(
      "%s.%s", method.getDeclaringClass().getName(), method.getName());

    logger.log(INFO, String.format(
      "Stripping PVP check from %s", fqMethodName));

    final WasPatchedCheck check = new WasPatchedCheck();

    method.instrument(new ExprEditor() {
      @Override
      public void edit(MethodCall m) throws CannotCompileException {
        if (p.test(m)) {
          // Replace call to Servers.isThisAPvpServer with literal false
          m.replace("$_ = false;");

          check.didPatch();
        }
      }
    });

    int patchCount = check.getPatchCount();

    if (patchCount != expectedPatches) {
      throw new NotFoundException(String.format(
        "Only %d patches were done, expected %d", patchCount, expectedPatches));
    }

    logger.log(INFO, String.format(
      "Successfully stripped PVP check from %s", fqMethodName));
  }

  // Convenience method for when we are only expecting to match the given
  // predicate once in the method body.
  private void stripPvpCheck(
      ClassPool pool, CtMethod method, Predicate<MethodCall> p)
      throws NotFoundException, BadBytecode, CannotCompileException {
    stripPvpCheck(pool, method, 1, p);
  }

  // Convenience method for when we want to use the default predicate but are
  // expecting more than a single replacement.
  private void stripPvpCheck(
      ClassPool pool, CtMethod method, int expectedPatches)
      throws NotFoundException, BadBytecode, CannotCompileException {
    stripPvpCheck(pool, method, expectedPatches, DEFAULT_PREDICATE);
  }

  // Convenience method when we only need to perform a single replacement with
  // no special checking.
  private void stripPvpCheck(ClassPool pool, CtMethod method)
      throws NotFoundException, BadBytecode, CannotCompileException {
    stripPvpCheck(pool, method, 1, DEFAULT_PREDICATE);
  }

  // Get a class by name and for each given method name, lookup a matching
  // method (by name alone - parameters ignored) and do stripPvpCheck for each
  private void patchClassMethods(
      ClassPool pool, String className, String[] methodNames)
      throws BadBytecode, CannotCompileException, NotFoundException {
    logStartPatch(logger, className);

    CtClass targetClass = pool.get(className);

    for (String methodName : methodNames) {
      CtMethod targetMethod =
        findMatchingMethod(targetClass, methodName);

      stripPvpCheck(pool, targetMethod);
    }

    logFinishPatch(logger, className);
  }

  private void patchClassMethods(
      ClassPool pool, String className, String methodName)
      throws BadBytecode, CannotCompileException, NotFoundException {
    patchClassMethods(pool, className, new String[] { methodName });
  }

  // Patches CreatureBehaviour.addVehicleOptions. Like the other method in
  // CreatureBehaviour, it's huge and contains logic for many vehicle options,
  // not just those related to animals / branding, so we don't want to blindly
  // strip all Servers.isThisAPvpServer checks. Our strategy here is to find
  // where the (initially unreachble, when pvp = true) action id for
  // MANAGE_ANIMAL is pushed into the stack, then find the nearest preceeding
  // INVOKESTATIC for isThisAPvpServer
  private void patchCBAddVehicleOptions(ClassPool pool, CtClass targetClass)
      throws NotFoundException, BadBytecode, CannotCompileException {
    CtMethod targetMethod =
      targetClass.getDeclaredMethods("addVehicleOptions")[0];

    final int targetPos = searchForInstruction(targetMethod, (ci, cp) -> {
      int actionAddPos = -1;
      int candidatePos = -1;

      while (ci.hasNext()) {
        int pos = ci.next();
        int op = ci.byteAt(pos);

        if (actionAddPos == -1) {
          if (op == SIPUSH) {
            int val = ci.s16bitAt(pos + 1);

            if (val == MANAGE_ANIMAL_ACTION) {
              // Found where the code for MANAGE_ANIMAL is pushed
              actionAddPos = pos;

              // Restart the iterator
              ci.begin();
            }
          }
        } else if (op == INVOKESTATIC) {
          int val = ci.s16bitAt(pos + 1);
          String methodName = cp.getMethodrefName(val);

          if (methodName.equals(PVP_CHECK_METHOD_NAME)) {
            if (pos < actionAddPos) {
              if (actionAddPos - pos < actionAddPos - candidatePos) {
                // We are before the actionAddPos, and closer to it than the
                // last isThisAPvpServer call. Once we have reached
                // actionAddPos, candidatePos will contain the position of the
                // last isThisAPvpServer call before it (or -1 if not found0.
                candidatePos = pos;
              }
            } else {
              // We've reached / gone past actionAddPos, the instruction we're
              // looking for isn't here.
              break;
            }
          }
        }
      }

      if (candidatePos != -1) {
        return candidatePos;
      }

      throw new NotFoundException("Could not find target instruction");
    });

    stripPvpCheck(
      pool, targetMethod,
      DEFAULT_PREDICATE.and(m -> m.indexOfBytecode() == targetPos)
    );
  }

  // Finds the instruction pointed to by a LOOKUPSWITCH for the given value.
  // switchIndex is assumed to be the index of the first byte of a LOOKUPSWITCH
  // instruction. Does not mutate the passed CodeIterator
  private int findPosForCase(CodeIterator ci, int switchIndex, int caseValue) {
    int pos = (switchIndex & ~3) + 4;
    // Not used
    //int def = switchIndex + ci.s32bitAt(pos);
    int count = ci.s32bitAt(pos += 4);
    int end = count * 8 + (pos +=4);

    for (; pos < end; pos += 8) {
      int label = ci.s32bitAt(pos);

      if (label == caseValue) {
        return ci.s32bitAt(pos + 4) + switchIndex;
      }

    }

    return -1;
  }

  // Patches CreatureBehaviour.action. This method contains a HUGE switch
  // statement for all the possible actions in the game.  We only watch to patch
  // the Servers.isThisAPvpServer check within the branch for MANAGE_ACTION.
  // Conveniently, the INVOKESTATIC for isThisAPvpServer is the first
  // instruction in that branch, so finding the instruction pointed to by the
  // LOOKUPSWITCH for MANAGE_ANIMAL is sufficient.
  private void patchCBAction(ClassPool pool, CtClass targetClass)
      throws NotFoundException, BadBytecode, CannotCompileException {
    CtMethod targetMethod =
      targetClass.getDeclaredMethods("action")[0];

    final int targetPos = searchForInstruction(targetMethod, (ci, cp) -> {
      while (ci.hasNext()) {
        int pos = ci.next();
        int op = ci.byteAt(pos);

        if (op == LOOKUPSWITCH) {
          // Found a LOOKUPSWITCH, let's see if it contains a branch for case:
          // MANAGE_ANIMAL
          int casePos = findPosForCase(ci, pos, MANAGE_ANIMAL_ACTION);

          if (casePos != -1) {
            // Found the where case for MANAGE_ANIMAL handled, let's just make
            // sure it's actually the instruction we're after before returning
            // it's index
            int caseOp = ci.byteAt(casePos);

            if (caseOp == INVOKESTATIC) {
              int val = ci.s16bitAt(casePos + 1);
              String methodName = cp.getMethodrefName(val);

              if (methodName.equals(PVP_CHECK_METHOD_NAME)) {
                return casePos;
              }
            }
          }
        }
      }

      throw new NotFoundException("Could not find target instruction");
    });

    stripPvpCheck(
      pool, targetMethod,
      DEFAULT_PREDICATE.and(m -> m.indexOfBytecode() == targetPos)
    );
  }

  // Need to do something a bit more complicated for this class, as we only want
  // to strip only some of the pvp checks within each method we patch
  private void patchCreatureBehaviour(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    String className = CREATURE_BEHAVIOUR_CLASS_NAME;

    logStartPatch(logger, className);

    CtClass targetClass = pool.get(className);

    patchCBAddVehicleOptions(pool, targetClass);
    patchCBAction(pool, targetClass);

    logFinishPatch(logger, className);
  }

  // Patches the reallyHandle_CMD_MOVE_INVENTORY method of the Communicator
  // class. This method is rather huge and we don't want to blindly strip every
  // Servers.isThisAPvpServer check from its body; only those related to animal
  // brands. This is done by finding all INVOKESTATIC isThisAPvpServer
  // instructions that are followed, in order, by IFNE, ALOAD, and an
  // INVOKEVIRTUAL for isBranded
  private void patchCommunicator(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    String className = COMMUNICATOR_CLASS_NAME;

    logStartPatch(logger, className);

    CtClass targetClass = pool.get(className);
    CtMethod targetMethod =
      targetClass.getDeclaredMethods("reallyHandle_CMD_MOVE_INVENTORY")[0];

    final List<LookaheadPredicate> sequence = Arrays.asList(
      (op, methodName) ->
        op == INVOKESTATIC && methodName.equals(PVP_CHECK_METHOD_NAME),
      (op, methodName) ->
        op == IFNE,
      (op, methodName) ->
        op == ALOAD,
      (op, methodName) ->
        op == INVOKEVIRTUAL && methodName.equals(BRAND_CHECK_METHOD_NAME)
    );

    final List<Integer> targets =
    searchForInstructions(targetMethod, (ci, cp) -> {
      return findSequence(ci, cp, sequence);
    });

    // Each time we strip a PVP check, the bytecode that replaces the
    // INVOKESTATIC for isThisAPvpServer is two bytes longer. For
    // isThisAPvpServer checks encountered by ExprEditor after the first one,
    // their position in the bytecode array will not match that of the original
    // bytecode (i.e. that which is in the targets List). We need to keep track
    // of how many modifications we've made, so we can subtract 8 * offsetCount
    // from the encountered index to find the instruction's original index in
    // the unmodified bytecode beforoe matching against the targets List
    final AtomicInteger offsetCount = new AtomicInteger();

    stripPvpCheck(pool, targetMethod, 2, DEFAULT_PREDICATE.and(m -> {
      int o = offsetCount.get();

      // Account for drift introduced by earlier modifications
      int pos = m.indexOfBytecode() - (o * 8);

      boolean found = targets.contains(pos);

      if (found) {
        // Got a matching instruction, so ExprEditor is going to modify this one
        // after we return. Update the patch counter.
        offsetCount.set(o + 1);
      }

      return found;
    }));

    logFinishPatch(logger, className);
  }

  @Override
  public void preInit() {
    ClassPool pool = HookManager.getInstance().getClassPool();

    try {
      logger.log(INFO, "Enabling PVP server animal permission management");

      patchClassMethods(pool, BRAND_CLASS_NAME, "addInitialPermissions");
      patchClassMethods(pool, CREATURE_CLASS_NAME, "canHavePermissions");
      patchClassMethods(pool, CREATURES_CLASS_NAME, "getManagedAnimalsFor");
      patchClassMethods(pool, MANAGE_MENU_CLASS_NAME, new String[] {
        "getBehavioursFor", "action"
      });

      patchCreatureBehaviour(pool);
      patchCommunicator(pool);

      logger.log(
        INFO, "Successfully enabled PVP server animal permission management");
    } catch (Exception e) {
      // TODO: Handle properly
      throw new HookException(e);
    }
  }

  // Helper class so we can assert the expected number of bytecode patches were
  // done.
  private static class WasPatchedCheck {
    private int patchesDone = 0;

    public void didPatch() {
      patchesDone++;
    }

    public int getPatchCount() {
      return patchesDone;
    }
  }

  @FunctionalInterface
  private static interface Searcher {
    // Takes a CodeIterator that may have already been moved to a non-zero
    // starting location and returns the index of the first matching instruction
    // at or after the CodeIterator's starting position. ConstPool is provided
    // so that method names can be resolved.
    public int search(CodeIterator ci, ConstPool cp)
        throws NotFoundException, BadBytecode;
  }

  // Convenience alias
  private static interface LookaheadPredicate
    extends BiPredicate<Integer, String> {}
}
