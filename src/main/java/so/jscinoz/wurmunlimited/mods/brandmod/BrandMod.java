package so.jscinoz.wurmunlimited.mods.brandmod;

import java.util.NoSuchElementException;
import java.util.logging.Logger;

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
import static javassist.bytecode.Opcode.INVOKESTATIC;
import static javassist.bytecode.Opcode.LOOKUPSWITCH;
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

  private static final String SERVERS_CLASS_NAME =
    "com.wurmonline.server.Servers";

  private static final String PVP_CHECK_METHOD_NAME = "isThisAPvpServer";

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

  private boolean isTarget(MethodCall m) {
    return m.getMethodName().equals(PVP_CHECK_METHOD_NAME) &&
           m.getClassName().equals(SERVERS_CLASS_NAME);
  }

  private void stripPvpCheck(ClassPool pool, CtMethod method)
      throws BadBytecode, CannotCompileException {
    String fqMethodName = String.format(
      "%s.%s", method.getDeclaringClass().getName(), method.getName());

    logger.log(INFO, String.format(
      "Stripping PVP check from %s", fqMethodName));

    method.instrument(new ExprEditor() {
      @Override
      public void edit(MethodCall m) throws CannotCompileException {
        if (isTarget(m)) {
          // Replace call to Servers.isThisAPvpServer with literal false
          m.replace("$_ = false;");
        }
      }
    });

    logger.log(INFO, String.format(
      "Successfully stripped PVP check from %s", fqMethodName));
  }

  private void mangleClassMethods(
      ClassPool pool, String className, String[] methodNames)
      throws BadBytecode, CannotCompileException, NotFoundException {
    logger.log(INFO, String.format("Mangling %s", className));

    CtClass targetClass = pool.get(className);

    for (String methodName : methodNames) {
      CtMethod targetMethod =
        findMatchingMethod(targetClass, methodName);

      stripPvpCheck(pool, targetMethod);
    }

    logger.log(INFO, String.format("Successfully mangled %s", className));
  }

  private void mangleClassMethods(
      ClassPool pool, String className, String methodName)
      throws BadBytecode, CannotCompileException, NotFoundException {
    mangleClassMethods(pool, className, new String[] { methodName });
  }

  private void mangleCBAddVehicleOptions(ClassPool pool, CtClass targetClass)
      throws NotFoundException, BadBytecode, CannotCompileException {
    // TODO: Find bytecode index first, then ExprEditor for the actual
    // modification
    CtMethod targetMethod =
      targetClass.getDeclaredMethods("addVehicleOptions")[0];

    MethodInfo mi = targetMethod.getMethodInfo();
    ConstPool cp = mi.getConstPool();
    CodeAttribute ca = mi.getCodeAttribute();
    CodeIterator ci = ca.iterator();

    int maybeTargetPos = -1;
    int actionAddPos = -1;

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
              actionAddPos - pos < actionAddPos - maybeTargetPos) {
            // We are before the actionAddPos, and closer to it than the last
            // isThisAPvpServer call
            maybeTargetPos = pos;
          }
        }
      }
    }

    if (maybeTargetPos == -1) {
      throw new NotFoundException("Could not find target instruction");
    }

    final int targetPos = maybeTargetPos;

    final WasPatchedCheck check = new WasPatchedCheck();

    targetMethod.instrument(new ExprEditor() {
      @Override
      public void edit(MethodCall m) throws CannotCompileException {
        if (isTarget(m)) {
          int pos = m.indexOfBytecode();

          if (pos == targetPos) {
            m.replace("$_ = false;");

            check.setPatched();
          }
        }
      }
    });

    if (!check.didPatch()) {
      throw new NotFoundException("Did not find target method during patching");
    }
  }

  private int findPosForCase(CodeIterator ci, int switchIndex, int caseValue)
      throws BadBytecode {
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

    MethodInfo mi = targetMethod.getMethodInfo();
    ConstPool cp = mi.getConstPool();
    CodeAttribute ca = mi.getCodeAttribute();
    CodeIterator ci = ca.iterator();

    int switchPos = -1;
    int targetPos = -1;

    while (ci.hasNext()) {
      int pos = ci.next();
      int op = ci.byteAt(pos);

      if (switchPos == -1) {
        if (op == LOOKUPSWITCH) {
          int casePos = findPosForCase(ca.iterator(), pos, 663); 

          if (casePos != -1) {
            int caseOp = ci.byteAt(casePos);

            if (caseOp == INVOKESTATIC) {
              int val = ci.s16bitAt(casePos + 1);
              String methodName = cp.getMethodrefName(val);

              if (methodName.equals(PVP_CHECK_METHOD_NAME)) {
                targetPos = casePos; 

                break;
              }
            }
          }
        }

      }
    }

    final int finalTargetPos = targetPos;

    if (targetPos == -1) {
      throw new NotFoundException("Could not find target instruction");
    }

    final WasPatchedCheck check = new WasPatchedCheck();

    targetMethod.instrument(new ExprEditor() {
      @Override
      public void edit(MethodCall m) throws CannotCompileException {
        if (isTarget(m)) {
          int pos = m.indexOfBytecode();

          if (pos == finalTargetPos) {
            m.replace("$_ = false;");

            check.setPatched();
          }
        }
      }
    });

    if (!check.didPatch()) {
      throw new NotFoundException("Did not find target method during patching");
    }
  }

  // Need to do something a bit more complicated for this class, as we only want
  // to strip some of the pvp checks within each method we mangle
  private void mangleCreatureBehaviour(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    CtClass targetClass = pool.get(CREATURE_BEHAVIOUR_CLASS_NAME);

    mangleCBAddVehicleOptions(pool, targetClass);
    mangleCBAction(pool, targetClass);
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

      logger.log(
        INFO, "Successfully enabled PVP server animal permission management");
    } catch (Exception e) {
      // TODO: Handle properly
      throw new HookException(e);
    }
  }

  private static class WasPatchedCheck {
    private boolean patchDone = false;

    public void setPatched() {
      patchDone = true;
    }

    public boolean didPatch() {
      return patchDone;
    }
  }
}
