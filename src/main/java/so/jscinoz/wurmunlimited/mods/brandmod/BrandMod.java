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

  private static final String CREATURE_CLASS_NAME =
    "com.wurmonline.server.creatures.Creature";

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
    String fqMethodName = String.format(
      "%s.%s", method.getDeclaringClass().getName(), method.getName());

    logger.log(INFO, String.format(
      "Stripping PVP check from %s", fqMethodName));

    method.instrument(new ExprEditor() {
      public void edit(MethodCall m) throws CannotCompileException {
        if (m.getMethodName().equals(PVP_CHECK_METHOD_NAME) &&
            m.getClassName().equals(SERVERS_CLASS_NAME)) {
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

      logger.log(
        INFO, "Successfully enabled PVP server animal permission management");
    } catch (Exception e) {
      // TODO: Handle properly
      throw new HookException(e);
    }
  }
}
