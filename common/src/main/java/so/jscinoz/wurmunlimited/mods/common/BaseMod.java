package so.jscinoz.wurmunlimited.mods.common;

import java.util.List;
import java.util.ArrayList;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.logging.Logger;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import static java.util.logging.Level.INFO;
import static javassist.bytecode.Opcode.INVOKESTATIC;
import static javassist.bytecode.Opcode.INVOKEVIRTUAL;

public abstract class BaseMod {
  // Default lookahead predicate used when the predicates List contains null
  // values
  private static final LookaheadPredicate DEFAULT_LOOKAHEAD_PREDICATE =
    (op, method) -> true;

  protected final Logger logger;

  protected BaseMod(Logger logger) {
    this.logger = logger;
  }

  // Finds the first method with the given name on the given class. Method
  // parameters are ignored.
  private static CtMethod findMatchingMethod(
      CtClass targetClass, String methodName)
      throws NotFoundException {
    for (CtMethod m : targetClass.getDeclaredMethods()) {
      if (m.getName().equals(methodName)) {
        return m;
      }
    }

    throw new NotFoundException(String.format(
      "Could not find method %s on %s", methodName, targetClass.getName()));
  }

  private void logStartPatch(String className) {
    logger.log(INFO, String.format("Patching %s", className));
  };

  private void logFinishPatch(String className) {
    logger.log(INFO, String.format("Successfully patched %s", className));
  };

  // Returns the index of the first byte of the first instruction within a
  // sequence of instructions that matches the given List of predicates. Will
  // throw if not present
  protected static int findSequence(
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

  protected static List<Integer> searchForInstructions(
      CtMethod method, Searcher searcher)
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

  protected static int searchForInstruction(CtMethod method, Searcher searcher)
      throws NotFoundException, BadBytecode {
    return searchForInstructions(method, searcher).get(0);
  }

  protected static int findNearestPreceding(
      CtMethod method, int preceding, Searcher searcher)
      throws NotFoundException, BadBytecode {
    MethodInfo mi = method.getMethodInfo();
    ConstPool cp = mi.getConstPool();
    CodeAttribute ca = mi.getCodeAttribute();
    CodeIterator ci = ca.iterator();

    int candidatePos = -1;

    while (ci.hasNext() && ci.lookAhead() < preceding) {
      try {
        int matchPos = searcher.search(ci, cp);

        if (preceding - matchPos < preceding - candidatePos) {
          // We're closer than before and still before the instuction at
          // 'preceding'
          candidatePos = matchPos;
        }
      } catch (NotFoundException e) {
        break;
      }
    }

    if (candidatePos != -1) {
      return candidatePos;
    }

    throw new NotFoundException("Could not find target instruction");
  }

  protected static int findNearestFollowing(
      CtMethod method, int following, Searcher searcher)
      throws NotFoundException, BadBytecode {
    MethodInfo mi = method.getMethodInfo();
    ConstPool cp = mi.getConstPool();
    CodeAttribute ca = mi.getCodeAttribute();
    CodeIterator ci = ca.iterator();

    ci.move(following);

    while (ci.hasNext()) {
      try {
        int matchPos = searcher.search(ci, cp);

        if (matchPos != -1) {
          return matchPos;
        }
      } catch (NotFoundException e) {
        break;
      }
    }

    throw new NotFoundException("Could not find target instruction");
  }


  // Finds the instruction pointed to by a LOOKUPSWITCH for the given value.
  // switchIndex is assumed to be the index of the first byte of a LOOKUPSWITCH
  // instruction. Does not mutate the passed CodeIterator
  protected static int findPosForCase(
      CodeIterator ci, int switchIndex, int caseValue) {
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

  // Runs the given patcher on all given methods, with logging
  protected void patchClassMethods(
      ClassPool pool, MethodPatcher patcher, String className,
      CtMethod... targetMethods)
      throws BadBytecode, CannotCompileException, NotFoundException {
    logStartPatch(className);

    for (CtMethod targetMethod : targetMethods) {
      patcher.patch(targetMethod);
    }

    logFinishPatch(className);
  }

  // Get a class by name and for each given method name, lookup a matching
  // method (by name alone - parameters ignored) and run the patcher for each
  protected void patchClassMethods(
      ClassPool pool, MethodPatcher patcher,
      String className, String... methodNames)
      throws BadBytecode, CannotCompileException, NotFoundException {
    logStartPatch(className);

    CtClass targetClass = pool.get(className);

    for (String methodName : methodNames) {
      CtMethod targetMethod =
        findMatchingMethod(targetClass, methodName);

      patcher.patch(targetMethod);
    }

    logFinishPatch(className);
  }

  protected void patchMethod(CtMethod targetMethod, MethodPatcher... patchers)
      throws BadBytecode, CannotCompileException, NotFoundException {
    for (MethodPatcher patcher : patchers) {
      patcher.patch(targetMethod);
    }
  }

  protected void patchClass(
      ClassPool pool, CtClass targetClass, ClassPatcher... patchers)
      throws BadBytecode, CannotCompileException, NotFoundException {
    String className = targetClass.getName();

    logStartPatch(className);

    for (ClassPatcher patcher : patchers) {
      patcher.patch(targetClass);
    }

    logFinishPatch(className);
  }

  protected void patchClass(
      ClassPool pool, String className, ClassPatcher... patchers)
      throws BadBytecode, CannotCompileException, NotFoundException {
    logStartPatch(className);

    CtClass targetClass = pool.get(className);

    for (ClassPatcher patcher : patchers) {
      patcher.patch(targetClass);
    }

    logFinishPatch(className);
  }

  protected void patchExpressions(
      CtMethod method, String logStartTmpl, String logEndTmpl,
      int expectedPatches, ExpressionPatcher patcher)
      throws CannotCompileException, NotFoundException {
    String fqMethodName = String.format(
      "%s.%s", method.getDeclaringClass().getName(), method.getName());

    logger.log(INFO, String.format(logStartTmpl, fqMethodName));

    final WasPatchedCheck check = new WasPatchedCheck();

    method.instrument(new ExprEditor() {
      @Override
      public void edit(MethodCall m) throws CannotCompileException {
        try {
          patcher.patch(m, check);
        } catch (BadBytecode | NotFoundException e) {
          e.printStackTrace();
          throw new CannotCompileException(e);
        }
      }
    });

    int patchCount = check.getPatchCount();

    if (patchCount != expectedPatches) {
      throw new NotFoundException(String.format(
        "Only %d patches were done, expected %d", patchCount, expectedPatches));
    }

    logger.log(INFO, String.format(logEndTmpl, fqMethodName));
  }

  // Helper class so we can assert the expected number of bytecode patches were
  // done.
  protected static class WasPatchedCheck {
    private int patchesDone = 0;

    public WasPatchedCheck() {}

    public void didPatch() {
      patchesDone++;
    }

    public int getPatchCount() {
      return patchesDone;
    }
  }

  @FunctionalInterface
  protected static interface Searcher {
    // Takes a CodeIterator that may have already been moved to a non-zero
    // starting location and returns the index of the first matching instruction
    // at or after the CodeIterator's starting position. ConstPool is provided
    // so that method names can be resolved.
    public int search(CodeIterator ci, ConstPool cp)
        throws NotFoundException, BadBytecode;
  }

  @FunctionalInterface
  protected static interface MethodPatcher {
    public void patch(CtMethod targetMethod)
        throws BadBytecode, NotFoundException, CannotCompileException;
  }

  @FunctionalInterface
  protected static interface ClassPatcher {
    public void patch(CtClass targetClass)
        throws BadBytecode, NotFoundException, CannotCompileException;
  }

  @FunctionalInterface
  protected static interface ExpressionPatcher {
    public void patch(MethodCall m, WasPatchedCheck check)
        throws BadBytecode, NotFoundException, CannotCompileException;
  }

  // Convenience alias
  protected static interface LookaheadPredicate
    extends BiPredicate<Integer, String> {}

  // Wurm constants
  protected static final class Wurm {
    public static final class Class {
      public static final String Servers = "com.wurmonline.server.Servers";

      public static final String Item = "com.wurmonline.server.items.Item";

      public static final String Action =
        "com.wurmonline.server.behaviours.Action";
      public static final String ItemBehaviour =
        "com.wurmonline.server.behaviours.ItemBehaviour";
      public static final String CreatureBehaviour =
        "com.wurmonline.server.behaviours.CreatureBehaviour";
      public static final String ManageMenu =
        "com.wurmonline.server.behaviours.ManageMenu";

      public static final String Brand =
        "com.wurmonline.server.creatures.Brand";
      public static final String Creature =
        "com.wurmonline.server.creatures.Creature";
      public static final String Creatures =
        "com.wurmonline.server.creatures.Creatures";
      public static final String Communicator =
        "com.wurmonline.server.creatures.Communicator";
    }

    public static final class Method {
      public static final String isBranded = "isBranded";
      public static final String isThisAPvpServer = "isThisAPvpServer";
      public static final String addInitialPermissions = "addInitialPermissions";
      public static final String canHavePermissions = "canHavePermissions";
      public static final String getManagedAnimalsFor = "getManagedAnimalsFor";
      public static final String getBehavioursFor = "getBehavioursFor";
      public static final String action = "action";

      public static final String isMetal = "isMetal";
      public static final String isIndestructible = "isIndestructible";
    }

    public static final class Action {
      public static final int SMELT = 519;
      public static final int MANAGE_ANIMAL = 663;
    }
  }
}
