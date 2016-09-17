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

import static java.util.logging.Level.INFO;
import static so.jscinoz.wurmunlimited.mods.common.ModSupport.*;

public class SmeltMod implements WurmServerMod, PreInitable {
  private static final String CREATURE_CLASS_NAME =
    "com.wurmonline.server.creatures.Creature";

  private static final Logger logger =
    Logger.getLogger(SmeltMod.class.getName());

  // TODO: Clean this up (only patch target instructions rather than all), and
  // split to separate mod
  private void patchKeyTest(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    String className = "com.wurmonline.server.behaviours.ItemBehaviour";

    logStartPatch(logger, className);

    CtClass targetClass = pool.get(className);
    CtMethod targetMethod =
      targetClass.getDeclaredMethod("action", new CtClass[] {
        pool.get("com.wurmonline.server.behaviours.Action"),
        pool.get(CREATURE_CLASS_NAME),
        pool.get("com.wurmonline.server.items.Item"),
        pool.get("com.wurmonline.server.items.Item"),
        CtClass.shortType,
        CtClass.floatType
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
        pool.get(CREATURE_CLASS_NAME),
        pool.get("com.wurmonline.server.items.Item"),
        pool.get("com.wurmonline.server.items.Item"),
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

    logFinishPatch(logger, className);
  }

  public void preInit() {
    ClassPool pool = HookManager.getInstance().getClassPool();

    try {
      logger.log(INFO, "Enabling key/lock smelting");

      patchKeyTest(pool);

      logger.log(INFO, "Successfully enabled key/lock smelting");
    } catch (Exception e) {
      // TODO: Handle properly
      throw new HookException(e);
    }
  }
}
