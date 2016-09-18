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

import so.jscinoz.wurmunlimited.mods.common.BaseMod;

import static java.util.logging.Level.INFO;
import static javassist.bytecode.Opcode.ALOAD;
import static javassist.bytecode.Opcode.IFNE;
import static javassist.bytecode.Opcode.LOOKUPSWITCH;
import static javassist.bytecode.Opcode.INVOKESTATIC;
import static javassist.bytecode.Opcode.INVOKEVIRTUAL;
import static javassist.bytecode.Opcode.SIPUSH;

public class BrandMod extends BaseMod implements WurmServerMod, PreInitable {
  // Default test for replacing a MethodCall with ExprEditor - simply checks
  // that the target MethodCall is for Servers.isThisAPvpServer
  private static final Predicate<MethodCall> DEFAULT_PREDICATE = m -> {
    return m.getMethodName().equals(Wurm.Method.isThisAPvpServer) &&
           m.getClassName().equals(Wurm.Class.Servers);
  };

  // Default patcher to strip the pvp check once in a method
  private final MethodPatcher DEFAULT_METHOD_PATCHER =
    targetMethod -> stripPvpCheck(targetMethod);

  public BrandMod() {
    super(Logger.getLogger(BrandMod.class.getName()));
  }

  // Replaces all Servers.isThisAPvpServer MethodCalls that match the given
  // predicate with a literal false, in the body of the given method. Will throw
  // if the replacement count is not equal to the expected patch count, so we
  // don't silently continue if the patching didn't go exactly as intended.
  private void stripPvpCheck(
      CtMethod method, int expectedPatches, final Predicate<MethodCall> p)
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
      CtMethod method, Predicate<MethodCall> p)
      throws NotFoundException, BadBytecode, CannotCompileException {
    stripPvpCheck(method, 1, p);
  }

  // Convenience method for when we want to use the default predicate but are
  // expecting more than a single replacement.
  private void stripPvpCheck(
      CtMethod method, int expectedPatches)
      throws NotFoundException, BadBytecode, CannotCompileException {
    stripPvpCheck(method, expectedPatches, DEFAULT_PREDICATE);
  }

  // Convenience method when we only need to perform a single replacement with
  // no special checking.
  private void stripPvpCheck(CtMethod method)
      throws NotFoundException, BadBytecode, CannotCompileException {
    stripPvpCheck(method, 1, DEFAULT_PREDICATE);
  }

  // Patches CreatureBehaviour.addVehicleOptions. Like the other method in
  // CreatureBehaviour, it's huge and contains logic for many vehicle options,
  // not just those related to animals / branding, so we don't want to blindly
  // strip all Servers.isThisAPvpServer checks. Our strategy here is to find
  // where the (initially unreachble, when pvp = true) action id for
  // MANAGE_ANIMAL is pushed into the stack, then find the nearest preceeding
  // INVOKESTATIC for isThisAPvpServer
  private final ClassPatcher patchCBAddVehicleOptions = targetClass -> {
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

            if (val == Wurm.Action.MANAGE_ANIMAL) {
              // Found where the code for MANAGE_ANIMAL is pushed
              actionAddPos = pos;

              // Restart the iterator
              ci.begin();
            }
          }
        } else if (op == INVOKESTATIC) {
          int val = ci.s16bitAt(pos + 1);
          String methodName = cp.getMethodrefName(val);

          if (methodName.equals(Wurm.Method.isThisAPvpServer)) {
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
      targetMethod,
      DEFAULT_PREDICATE.and(m -> m.indexOfBytecode() == targetPos)
    );
  };

  // Patches CreatureBehaviour.action. This method contains a HUGE switch
  // statement for all the possible actions in the game.  We only watch to patch
  // the Servers.isThisAPvpServer check within the branch for MANAGE_ACTION.
  // Conveniently, the INVOKESTATIC for isThisAPvpServer is the first
  // instruction in that branch, so finding the instruction pointed to by the
  // LOOKUPSWITCH for MANAGE_ANIMAL is sufficient.
  private final ClassPatcher patchCBAction = targetClass -> {
    CtMethod targetMethod =
      targetClass.getDeclaredMethods("action")[0];

    final int targetPos = searchForInstruction(targetMethod, (ci, cp) -> {
      while (ci.hasNext()) {
        int pos = ci.next();
        int op = ci.byteAt(pos);

        if (op == LOOKUPSWITCH) {
          // Found a LOOKUPSWITCH, let's see if it contains a branch for case:
          // MANAGE_ANIMAL
          int casePos = findPosForCase(ci, pos, Wurm.Action.MANAGE_ANIMAL);

          if (casePos != -1) {
            // Found the where case for MANAGE_ANIMAL handled, let's just make
            // sure it's actually the instruction we're after before returning
            // it's index
            int caseOp = ci.byteAt(casePos);

            if (caseOp == INVOKESTATIC) {
              int val = ci.s16bitAt(casePos + 1);
              String methodName = cp.getMethodrefName(val);

              if (methodName.equals(Wurm.Method.isThisAPvpServer)) {
                return casePos;
              }
            }
          }
        }
      }

      throw new NotFoundException("Could not find target instruction");
    });

    stripPvpCheck(
      targetMethod,
      DEFAULT_PREDICATE.and(m -> m.indexOfBytecode() == targetPos)
    );
  };

  // Need to do something a bit more complicated for this class, as we only want
  // to strip only some of the pvp checks within each method we patch
  private void patchCreatureBehaviour(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    CtClass targetClass = pool.get(Wurm.Class.CreatureBehaviour);

    patchClass(pool, targetClass, patchCBAddVehicleOptions, patchCBAction);
  }

  // Patches the reallyHandle_CMD_MOVE_INVENTORY method of the Communicator
  // class. This method is rather huge and we don't want to blindly strip every
  // Servers.isThisAPvpServer check from its body; only those related to animal
  // brands. This is done by finding all INVOKESTATIC isThisAPvpServer
  // instructions that are followed, in order, by IFNE, ALOAD, and an
  // INVOKEVIRTUAL for isBranded
  private void patchCommunicator(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    String className = Wurm.Class.Communicator;

    final List<LookaheadPredicate> sequence = Arrays.asList(
      (op, methodName) ->
        op == INVOKESTATIC && methodName.equals(Wurm.Method.isThisAPvpServer),
      (op, methodName) ->
        op == IFNE,
      (op, methodName) ->
        op == ALOAD,
      (op, methodName) ->
        op == INVOKEVIRTUAL && methodName.equals(Wurm.Method.isBranded)
    );

    patchClassMethods(pool, targetMethod -> {
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

      stripPvpCheck(targetMethod, 2, DEFAULT_PREDICATE.and(m -> {
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
    }, className, "reallyHandle_CMD_MOVE_INVENTORY");
  }

  @Override
  public void preInit() {
    ClassPool pool = HookManager.getInstance().getClassPool();

    try {
      logger.log(INFO, "Enabling PVP server animal permission management");

      patchClassMethods(
        pool, DEFAULT_METHOD_PATCHER,
        Wurm.Class.Brand, Wurm.Method.addInitialPermissions);

      patchClassMethods(
        pool, DEFAULT_METHOD_PATCHER,
        Wurm.Class.Creature, Wurm.Method.canHavePermissions);

      patchClassMethods(
        pool, DEFAULT_METHOD_PATCHER,
        Wurm.Class.Creatures, Wurm.Method.getManagedAnimalsFor);

      patchClassMethods(
        pool, DEFAULT_METHOD_PATCHER, Wurm.Class.ManageMenu,
        Wurm.Method.getBehavioursFor, Wurm.Method.action);

      patchCreatureBehaviour(pool);
      patchCommunicator(pool);

      logger.log(
        INFO, "Successfully enabled PVP server animal permission management");
    } catch (Exception e) {
      // TODO: Handle properly
      throw new HookException(e);
    }
  }
}
