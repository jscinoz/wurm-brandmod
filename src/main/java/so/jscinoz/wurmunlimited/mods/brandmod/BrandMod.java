package so.jscinoz.wurmunlimited.mods.brandmod;

import java.lang.FunctionalInterface;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.logging.Logger;
import java.util.function.BiFunction;
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

  private static final Predicate<MethodCall> DEFAULT_PREDICATE = m -> {
    return m.getMethodName().equals(PVP_CHECK_METHOD_NAME) &&
           m.getClassName().equals(SERVERS_CLASS_NAME);
  };

  private static final LookaheadPredicate DEFAULT_LOOKAHEAD_PREDICATE =
    (op, method) -> true;

  private static final Logger logger =
    Logger.getLogger(BrandMod.class.getName());

  private CtMethod findMatchingMethod(CtClass targetClass, String methodName) {
    for (CtMethod m : targetClass.getDeclaredMethods()) {
      if (m.getName().equals(methodName)) {
        return m;
      }
    }

    throw new NoSuchElementException(String.format(
      "Could not find method %s on %s", methodName, targetClass.getName()));
  }

  private int searchForInstruction(CtMethod method, Searcher searcher)
      throws NotFoundException, BadBytecode {
    return searchForInstructions(method, searcher).get(0);
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

  private int findSequence(
      CodeIterator ci, ConstPool cp,
      List<LookaheadPredicate> predicates)
      throws NotFoundException, BadBytecode {
    int pos;

    final int maxIndex = predicates.size() - 1;
    int testIndex = 0;

    int seqStart = -1;;

    while (ci.hasNext()) {
      pos = ci.next();
      int op = ci.byteAt(pos);

      String methodName = (op == INVOKESTATIC || op == INVOKEVIRTUAL)
        ? cp.getMethodrefName(ci.s16bitAt(pos + 1))
        : null;

      LookaheadPredicate p = predicates.get(testIndex);

      if (p == null) {
        p =  DEFAULT_LOOKAHEAD_PREDICATE;
      }

      if (p.test(op, methodName)) {
        if (seqStart == -1 || testIndex == 0) {
          seqStart = pos;
        }

        if (maxIndex == testIndex) {
          return seqStart;
        }

        testIndex++;
      } else {
        testIndex = 0;
      }
    }

    throw new NotFoundException("No matching bytecode sequence found");
  }

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

  private void stripPvpCheck(
      ClassPool pool, CtMethod method, Predicate<MethodCall> p)
      throws NotFoundException, BadBytecode, CannotCompileException {
    stripPvpCheck(pool, method, 1, p);
  }

  private void stripPvpCheck(
      ClassPool pool, CtMethod method, int expectedPatches)
      throws NotFoundException, BadBytecode, CannotCompileException {
    stripPvpCheck(pool, method, expectedPatches, DEFAULT_PREDICATE);
  }

  private void stripPvpCheck(ClassPool pool, CtMethod method)
      throws NotFoundException, BadBytecode, CannotCompileException {
    stripPvpCheck(pool, method, 1, DEFAULT_PREDICATE);
  }

  private void logStartMangle(String className) {
    logger.log(INFO, String.format("Mangling %s", className));
  };

  private void logFinishMangle(String className) {
    logger.log(INFO, String.format("Successfully mangled %s", className));
  };

  private void mangleClassMethods(
      ClassPool pool, String className, String[] methodNames)
      throws BadBytecode, CannotCompileException, NotFoundException {
    logStartMangle(className);

    CtClass targetClass = pool.get(className);

    for (String methodName : methodNames) {
      CtMethod targetMethod =
        findMatchingMethod(targetClass, methodName);

      stripPvpCheck(pool, targetMethod);
    }

    logFinishMangle(className);
  }

  private void mangleClassMethods(
      ClassPool pool, String className, String methodName)
      throws BadBytecode, CannotCompileException, NotFoundException {
    mangleClassMethods(pool, className, new String[] { methodName });
  }

  private void mangleCBAddVehicleOptions(ClassPool pool, CtClass targetClass)
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

            if (val == 663) {
              // Found where action 663 (MANAGE_ANIMAL) is pushed
              actionAddPos = pos;

              // Restart the iterator
              ci.begin();
            }
          }
        } else if (op == INVOKESTATIC) {
          int val = ci.s16bitAt(pos + 1);
          String methodName = cp.getMethodrefName(val);

          if (methodName.equals(PVP_CHECK_METHOD_NAME)) {
            if (pos < actionAddPos &&
                actionAddPos - pos < actionAddPos - candidatePos) {
              // We are before the actionAddPos, and closer to it than the
              // last isThisAPvpServer call
              candidatePos = pos;
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

  // Does not move the passed CodeIterator
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

  private void mangleCBAction(ClassPool pool, CtClass targetClass)
      throws NotFoundException, BadBytecode, CannotCompileException {
    CtMethod targetMethod =
      targetClass.getDeclaredMethods("action")[0];

    final int targetPos = searchForInstruction(targetMethod, (ci, cp) -> {
      int switchPos = -1;
      while (ci.hasNext()) {
        int pos = ci.next();
        int op = ci.byteAt(pos);

        if (switchPos == -1) {
          if (op == LOOKUPSWITCH) {
            int casePos = findPosForCase(ci, pos, 663);

            if (casePos != -1) {
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
      }

      throw new NotFoundException("Could not find target instruction");
    });

    stripPvpCheck(
      pool, targetMethod,
      DEFAULT_PREDICATE.and(m -> m.indexOfBytecode() == targetPos)
    );
  }

  // Need to do something a bit more complicated for this class, as we only want
  // to strip some of the pvp checks within each method we mangle
  private void mangleCreatureBehaviour(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    String className = CREATURE_BEHAVIOUR_CLASS_NAME;

    logStartMangle(className);

    CtClass targetClass = pool.get(className);

    mangleCBAddVehicleOptions(pool, targetClass);
    mangleCBAction(pool, targetClass);

    logFinishMangle(className);
  }

  private void mangleCommunicator(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    String className = COMMUNICATOR_CLASS_NAME;

    logStartMangle(className);

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

    final AtomicInteger offsetCount = new AtomicInteger();;

    stripPvpCheck(pool, targetMethod, 2, DEFAULT_PREDICATE.and(m -> {
      int o = offsetCount.get();

      int pos = m.indexOfBytecode() - (o * 8);

      boolean found = targets.contains(pos);

      if (found) {
        offsetCount.set(o + 1);
      }

      return found;
    }));

    logFinishMangle(className);
  }

  @Override
  public void preInit() {
    ClassPool pool = HookManager.getInstance().getClassPool();

    try {
      logger.log(INFO, "Enabling PVP server animal permission management");

      mangleClassMethods(pool, BRAND_CLASS_NAME, "addInitialPermissions");
      mangleClassMethods(pool, CREATURE_CLASS_NAME, "canHavePermissions");
      mangleClassMethods(pool, CREATURES_CLASS_NAME, "getManagedAnimalsFor");
      mangleClassMethods(pool, MANAGE_MENU_CLASS_NAME, new String[] {
        "getBehavioursFor", "action"
      });

      mangleCreatureBehaviour(pool);
      mangleCommunicator(pool);

      logger.log(
        INFO, "Successfully enabled PVP server animal permission management");
    } catch (Exception e) {
      // TODO: Handle properly
      throw new HookException(e);
    }
  }

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
    public int search(CodeIterator ci, ConstPool cp)
        throws NotFoundException, BadBytecode;
  }

  private static interface LookaheadPredicate
    extends BiPredicate<Integer, String> {}
}
