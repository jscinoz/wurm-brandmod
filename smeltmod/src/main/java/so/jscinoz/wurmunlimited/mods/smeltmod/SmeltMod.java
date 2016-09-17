package so.jscinoz.wurmunlimited.mods.smeltmod;

import java.util.logging.Logger;

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

public class SmeltMod extends BaseMod implements WurmServerMod, PreInitable {
  public SmeltMod() {
    super(Logger.getLogger(SmeltMod.class.getName()));
  }

  // TODO: Clean this up (only patch target instructions rather than all), and
  // split to separate mod
  private final ClassPatcher patchItemBehaviour = targetClass -> {
    ClassPool pool = targetClass.getClassPool();
    CtMethod targetMethod =
      targetClass.getDeclaredMethod("action", new CtClass[] {
        pool.get("com.wurmonline.server.behaviours.Action"),
        pool.get(Wurm.Class.Creature),
        pool.get(Wurm.Class.Item),
        pool.get(Wurm.Class.Item),
        CtClass.shortType,
        CtClass.floatType,
      });

    targetMethod.instrument(new ExprEditor() {
      @Override
      public void edit(MethodCall m) throws CannotCompileException {
        if (m.getMethodName().equals("isIndestructible")) {
          System.err.println("1: got isIndestructible");

          m.replace("$_ = $0.isKey() || $0.isLock() ? false : $proceed($$);");
        }
      }
    });

    targetMethod =
      targetClass.getDeclaredMethod("getBehavioursFor", new CtClass[] {
        pool.get(Wurm.Class.Creature),
        pool.get(Wurm.Class.Item),
        pool.get(Wurm.Class.Item),
      });

    targetMethod.instrument(new ExprEditor() {
      @Override
      public void edit(MethodCall m) throws CannotCompileException {
        if (m.getMethodName().equals("isIndestructible")) {
          System.err.println("2: got isIndestructible");

          m.replace("$_ = $0.isKey() || $0.isLock() ? false : $proceed($$);");
        } else if (m.getMethodName().equals("isMetal")) {
          System.err.println("2: got isMetal");

          m.replace("$_ = $0.isKey() || $0.isLock() ? true : $proceed($$);");
        }
      }
    });
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
