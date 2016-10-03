package so.jscinoz.wurmunlimited.mods.doormod;

import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;

import javassist.Loader;

import static java.util.logging.Level.INFO;

public class DoorMod implements WurmServerMod, PreInitable {
  private static final String STRUCTURE_CLASS =
    "com.wurmonline.server.structures.Structure";

  @Override
  public void preInit() {
    Loader loader = HookManager.getInstance().getLoader();
    TypePool pool = TypePool.Default.ofClassPath();
    TypeDescription structureType = pool.describe(STRUCTURE_CLASS).resolve();
    ClassFileLocator locator = ClassFileLocator.ForClassLoader.ofClassPath();

    new ByteBuddy()
      .redefine(structureType, locator)
      .method(ElementMatchers.named("needsDoor"))
      .intercept(FixedValue.value(false))
      .make()
      .load(loader);
  }
}
