package so.jscinoz.wurmunlimited.mods.common;

import java.util.logging.Logger;
import static java.util.logging.Level.INFO;
import static so.jscinoz.wurmunlimited.mods.common.ModSupport.*;

public final class ModSupport {
  public static void logStartPatch(Logger logger, String className) {
    logger.log(INFO, String.format("Patching %s", className));
  };

  public static void logFinishPatch(Logger logger, String className) {
    logger.log(INFO, String.format("Successfully patched %s", className));
  };
}
