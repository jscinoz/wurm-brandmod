package so.jscinoz.wurmunlimited.mods.doormod;

import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;

import net.bytebuddy.ByteBuddy;

// import so.jscinoz.wurmunlimited.mods.common.BaseMod;

import static java.util.logging.Level.INFO;

public class DoorMod implements WurmServerMod, PreInitable {
  @Override
  public void preInit() {
    new ByteBuddy()
      .rebase(com.wurmonline.server.structures.Structure.class);
    throw new HookException("TODO");
  }
}
