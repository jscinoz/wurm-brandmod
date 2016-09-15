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
import javassist.bytecode.BadBytecode;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import static java.util.logging.Level.INFO;

public class BrandMod implements WurmServerMod, PreInitable {
  private static final String BRAND_CLASS_NAME =
    "com.wurmonline.server.creatures.Brand";

  private static final String MANAGE_MENU_CLASS_NAME =
    "com.wurmonline.server.behaviours.ManageMenu";

  private static final String CREATURES_CLASS_NAME =
    "com.wurmonline.server.creatures.Creatures";

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

  private void stripPvpCheck(ClassPool pool, CtMethod method)
      throws BadBytecode, CannotCompileException {
    MethodInfo mi = method.getMethodInfo();

    method.instrument(new ExprEditor() {
      public void edit(MethodCall m) throws CannotCompileException {
        if (m.getMethodName().equals(PVP_CHECK_METHOD_NAME) &&
            m.getClassName().equals(SERVERS_CLASS_NAME)) {
          // Replace call to Servers.isThisAPvpServer with literal false
          m.replace("$_ = false;");
        }
      }
    });

    mi.rebuildStackMap(pool);
  }

  private void mangleBrandClass(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    CtClass targetClass = pool.get(BRAND_CLASS_NAME);

    CtMethod targetMethod =
      findMatchingMethod(targetClass, "addInitialPermissions");

    stripPvpCheck(pool, targetMethod);
  }

  private void mangleManageMenuClass(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    CtClass targetClass = pool.get(MANAGE_MENU_CLASS_NAME);
    String[] methodNames = { "getBehavioursFor", "action" };

    for (String methodName : methodNames) {
      CtMethod targetMethod =
        findMatchingMethod(targetClass, methodName);

      stripPvpCheck(pool, targetMethod);
    }
  }

  private void mangleCreaturesClass(ClassPool pool)
      throws BadBytecode, CannotCompileException, NotFoundException {
    CtClass targetClass = pool.get(CREATURES_CLASS_NAME);

    CtMethod targetMethod =
      findMatchingMethod(targetClass, "getManagedAnimalsFor");

    stripPvpCheck(pool, targetMethod);
  }

  @Override
  public void preInit() {
    ClassPool pool = HookManager.getInstance().getClassPool();

    try {
      mangleBrandClass(pool);
      mangleManageMenuClass(pool);
      mangleCreaturesClass(pool);
    } catch (Exception e) {
      // TODO: Handle properly
      throw new HookException(e);
    }
  }
}
