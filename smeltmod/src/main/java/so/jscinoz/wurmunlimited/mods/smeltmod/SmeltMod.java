package so.jscinoz.wurmunlimited.mods.smeltmod;

import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;

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

import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;

import so.jscinoz.wurmunlimited.mods.common.BaseMod;

import static java.util.logging.Level.INFO;
import static javassist.bytecode.Opcode.SIPUSH;
import static javassist.bytecode.Opcode.INVOKEVIRTUAL;

public class SmeltMod extends BaseMod implements WurmServerMod, PreInitable {
  private static final String REPLACEMENT_IS_METAL =
    "$_ = $0.isKey() || $0.isLock() ? true : $proceed($$);";

  private static final String REPLACEMENT_IS_INDESTRUCTIBLE =
    "$_ = $0.isKey() || $0.isLock() ? false: $proceed($$);";

  private static final Searcher SMELT_ACTION_SEARCHER = (ci, cp) -> {
    while (ci.hasNext()) {
      int pos = ci.next();
      int op = ci.byteAt(pos);

      if (op == SIPUSH) {
        int val = ci.s16bitAt(pos + 1);

        if (val == Wurm.Action.SMELT) {
          // Found where the code for SMELT is pushed
          return pos;
        }
      }
    }

    throw new NotFoundException("Could not find target instruction");
  };

  private static final Searcher IS_INDESTRUCTIBLE_SEARCHER = (ci, cp) -> {
    int pos = ci.next();
    int op = ci.byteAt(pos);

    if (op == INVOKEVIRTUAL) {
      int val = ci.s16bitAt(pos + 1);
      String methodrefName = cp.getMethodrefName(val);

      if (methodrefName.equals(Wurm.Method.isIndestructible)) {
        return pos;
      }
    }

    return -1;
  };

  private static final Searcher IS_METAL_SEARCHER = (ci, cp) -> {
    int pos = ci.next();
    int op = ci.byteAt(pos);

    if (op == INVOKEVIRTUAL) {
      int val = ci.s16bitAt(pos + 1);
      String methodrefName = cp.getMethodrefName(val);

      if (methodrefName.equals(Wurm.Method.isMetal)) {
        return pos;
      }
    }

    return -1;
  };

  public SmeltMod() {
    super(Logger.getLogger(SmeltMod.class.getName()));
  }

  private final MethodPatcher patchAction = targetMethod -> {
    patchExpressions(
      targetMethod,
      "Patching isMetal and isIndestructible checks from %s",
      "Successfully patched isMetal and isIndestructible checks from %s",
      2,
      (m, check) -> {
        String methodName = m.getMethodName();
        int methodPos  = m.indexOfBytecode();

        if (methodName.equals(Wurm.Method.isIndestructible) ||
            methodName.equals(Wurm.Method.isMetal)) {
          int actionAddPos =
            searchForInstruction(targetMethod, SMELT_ACTION_SEARCHER);

          if (methodName.equals(Wurm.Method.isIndestructible)) {
            int targetPos = findNearestFollowing(
              targetMethod, actionAddPos, IS_INDESTRUCTIBLE_SEARCHER);

            if (methodPos == targetPos) {
              m.replace(REPLACEMENT_IS_INDESTRUCTIBLE);
              check.didPatch();
            }
          } else if (methodName.equals(Wurm.Method.isMetal)) {
            int targetPos = findNearestFollowing(
              targetMethod, actionAddPos, IS_METAL_SEARCHER);

            if (methodPos == targetPos) {
              m.replace(REPLACEMENT_IS_METAL);
              check.didPatch();
            }
          }
        }
      }
    );
  };

  private final MethodPatcher patchGetBehavioursFor = targetMethod -> {
    patchExpressions(
      targetMethod,
      "Patching isMetal and isIndestructible checks from %s",
      "Successfully patched isMetal and isIndestructible checks from %s",
      2,
      (m, check) -> {
        int methodPos = m.indexOfBytecode();

        String methodName = m.getMethodName();

        if (methodName.equals(Wurm.Method.isIndestructible) ||
            methodName.equals(Wurm.Method.isMetal)) {
          final int actionAddPos =
          searchForInstruction(targetMethod, SMELT_ACTION_SEARCHER);

          if (methodName.equals(Wurm.Method.isIndestructible)) {
            final int targetPos =
            findNearestPreceding(
              targetMethod, actionAddPos, IS_INDESTRUCTIBLE_SEARCHER);

            if (methodPos == targetPos) {
              m.replace(REPLACEMENT_IS_INDESTRUCTIBLE);
              check.didPatch();
            }
          } else if (methodName.equals(Wurm.Method.isMetal)) {
            final int targetPos =
            findNearestPreceding(
              targetMethod, actionAddPos, IS_METAL_SEARCHER);

            if (methodPos == targetPos) {
              m.replace(REPLACEMENT_IS_METAL);
              check.didPatch();
            }
          }
        }
      }
    );
  };

  private final ClassPatcher patchItemBehaviour = targetClass -> {
    ClassPool pool = targetClass.getClassPool();

    patchMethod(
      targetClass.getDeclaredMethod(Wurm.Method.action, new CtClass[] {
        pool.get(Wurm.Class.Action),
        pool.get(Wurm.Class.Creature),
        pool.get(Wurm.Class.Item),
        pool.get(Wurm.Class.Item),
        CtClass.shortType,
        CtClass.floatType,
      }),
      patchAction
    );

    patchMethod(
      targetClass.getDeclaredMethod(Wurm.Method.getBehavioursFor, new CtClass[] {
        pool.get(Wurm.Class.Creature),
        pool.get(Wurm.Class.Item),
        pool.get(Wurm.Class.Item),
      }),
      patchGetBehavioursFor
    );
  };

  public void preInit() {
    ClassPool pool = HookManager.getInstance().getClassPool();

    try {
      logger.log(INFO, "Enabling key/lock smelting");

      patchClass(pool, Wurm.Class.ItemBehaviour, patchItemBehaviour);

      logger.log(INFO, "Successfully enabled key/lock smelting");
    } catch (Exception e) {
      // TODO: Handle properly
      throw new HookException(e);
    }
  }
}
