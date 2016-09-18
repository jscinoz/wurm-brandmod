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
  public SmeltMod() {
    super(Logger.getLogger(SmeltMod.class.getName()));
  }

  private final MethodPatcher patchAction = targetMethod -> {
    targetMethod.instrument(new ExprEditor() {
      @Override
      public void edit(MethodCall m) throws CannotCompileException {
        if (m.getMethodName().equals("isIndestructible")) {
          System.err.println("1: got isIndestructible");

          m.replace("$_ = $0.isKey() || $0.isLock() ? false : $proceed($$);");
        }
      }
    });
  };

  private final MethodPatcher patchGetBehavioursFor = targetMethod -> {
    final int actionAddPos = searchForInstruction(targetMethod, (ci, cp) -> {
      int candidatePos = -1;

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
    });

    System.err.println("519 is pushed at " + actionAddPos + " (should be 4952)");

    final int isIndestructibleTargetPos =
    findNearestPreceding(targetMethod, actionAddPos, (ci, cp) -> {
      while (ci.hasNext()) {
        int pos = ci.next();
        int op = ci.byteAt(pos);

        if (op == INVOKEVIRTUAL) {
          int val = ci.s16bitAt(pos + 1);
          String methodName = cp.getMethodrefName(val);

          if (methodName.equals(Wurm.Method.isIndestructible)) {
            return pos;
          }
        }
      }

      throw new NotFoundException("Could not find target instruction");
    });

    final int isMetalTargetPos =
    findNearestPreceding(targetMethod, actionAddPos, (ci, cp) -> {
      while (ci.hasNext()) {
        int pos = ci.next();
        int op = ci.byteAt(pos);

        if (op == INVOKEVIRTUAL) {
          int val = ci.s16bitAt(pos + 1);
          String methodName = cp.getMethodrefName(val);

          if (methodName.equals(Wurm.Method.isMetal)) {
            return pos;
          }
        }
      }

      throw new NotFoundException("Could not find target instruction");
    });


    final AtomicInteger patchCount = new AtomicInteger();

    targetMethod.instrument(new ExprEditor() {
      @Override
      public void edit(MethodCall m) throws CannotCompileException {
        int o = patchCount.get();
        int methodPos = m.indexOfBytecode() - (o * 8);

        if (m.getMethodName().equals(Wurm.Method.isIndestructible) &&
            methodPos == isIndestructibleTargetPos) {
          System.err.println("2: got target isIndestructible");

          m.replace("$_ = $0.isKey() || $0.isLock() ? false : $proceed($$);");
        } else if (m.getMethodName().equals(Wurm.Method.isMetal) &&
                   methodPos == isMetalTargetPos) {
          System.err.println("2: got target isMetal");

          m.replace("$_ = $0.isKey() || $0.isLock() ? true : $proceed($$);");
        }
      }
    });
  };

  // TODO: Clean this up (only patch target instructions rather than all), and
  // split to separate mod
  private final ClassPatcher patchItemBehaviour = targetClass -> {
    ClassPool pool = targetClass.getClassPool();

    /*
    patchMethod(
      targetClass.getDeclaredMethod(Wurm.Method.action, new CtClass[] {
        pool.get("com.wurmonline.server.behaviours.Action"),
        pool.get(Wurm.Class.Creature),
        pool.get(Wurm.Class.Item),
        pool.get(Wurm.Class.Item),
        CtClass.shortType,
        CtClass.floatType,
      }),
      patchAction
    );
    */

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
