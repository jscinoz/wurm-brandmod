package so.jscinoz.wurmunlimited.mods.brandmod;

import java.util.NoSuchElementException;

import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;

import javassist.ByteArrayClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

import org.apache.bcel.Repository;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ClassGen;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.INVOKESTATIC;
import org.apache.bcel.generic.ICONST;
import org.apache.bcel.generic.GETSTATIC;
import org.apache.bcel.generic.LDC;
import org.apache.bcel.generic.INVOKEVIRTUAL;
import org.apache.bcel.util.ClassPath.ClassFile;

public class BrandMod implements WurmServerMod, PreInitable {
  private static final String BRAND_CLASS_NAME =
    "com.wurmonline.server.creatures.Brand";

  private static final String SERVERS_CLASS_NAME =
    "com.wurmonline.server.Servers";

  private InstructionHandle findPvpCheck(
      InstructionList il, ConstantPoolGen cpGen) {
    for (InstructionHandle ih : il) {
      Instruction i = ih.getInstruction(); 

      if (i instanceof INVOKESTATIC) {
        INVOKESTATIC target = (INVOKESTATIC) i;

        String className = target.getClassName(cpGen);
        String methodName = target.getMethodName(cpGen);

        System.err.println(className);

        if (className.equals(SERVERS_CLASS_NAME) &&
            methodName.equals("isThisAPvpServer")) {
          return ih;
        }
      }
    }

    throw new NoSuchElementException("Could not find target instruction");
  }

  private CtMethod findTargetMethod(CtClass injectedClass) {
    for (CtMethod m : injectedClass.getMethods()) {
      System.err.println(m.getName());
      if (m.getName().equals("addInitialPermissions")) {
        return m;
      }
    }

    throw new NoSuchElementException("Could not find target method");
  }

  private Method findTargetMethod(JavaClass brandClass) {
    for (Method m : brandClass.getMethods()) {
      if (m.getName().equals("addInitialPermissions")) {
        return m;
      }
    }

    throw new NoSuchElementException("Could not find target method");
  }

  // TODO: Refactor to use javassist alone
  public void preInit() {
    try {
      JavaClass brandClass = Repository.lookupClass(BRAND_CLASS_NAME);
      ClassGen cgen = new ClassGen(brandClass);
      ConstantPoolGen cpGen = cgen.getConstantPool();

      Method targetMethod = findTargetMethod(brandClass);
      MethodGen mgen = new MethodGen(targetMethod, BRAND_CLASS_NAME, cpGen);
      InstructionList il = mgen.getInstructionList();

      // Find the instruction where Servers.isThisAPvpServer is called
      InstructionHandle target = findPvpCheck(il, cpGen);
      
      // Replacement instruction that just pushes a zero onto the stack (i.e. so
      // we can pretend Servers.isThisAPvpServer always returns false
      Instruction replacement = new ICONST(0);

      // Replace the instruction at the target position with our replacement
      target.setInstruction(replacement);

      // XXX: I don't think we need this
      // il.update();

      InstructionList logging = new InstructionList();
      Instruction i1 = new GETSTATIC(cgen.getConstantPool().addFieldref("java/lang/System", "out", "Ljava/io/PrintStream;"));
      Instruction i2 = new LDC(cgen.getConstantPool().addString("XXX: TEST DEBUG"));
      Instruction i3 = new INVOKEVIRTUAL(cgen.getConstantPool().addMethodref("java/io/PrintStream", "println", "(Ljava/lang/String;)V"));
      logging.append(i1);
      logging.append(i2);
      logging.append(i3);

      //il.insert(logging);
      
      mgen.setMaxStack();
      mgen.setMaxLocals();
      // XXX: needed?
      mgen.update();

      // Let's have a look at the updated InstructionList
      for (InstructionHandle ih : il) {
        System.err.println(ih);
      }

      // Replace the original method with our modified one
      cgen.replaceMethod(targetMethod, mgen.getMethod());

      // Release the instruction handles
      il.dispose();

      //ClassFile origClassFile = Repository.lookupClassFile(origClass.getName());
      byte[] classData = cgen.getJavaClass().getBytes();

      // Inject our modified class
      //cgen.getJavaClass().dump(origClassFile.getPath());

      ClassPool cp = HookManager.getInstance().getClassPool();

      cp.insertClassPath(new ByteArrayClassPath(BRAND_CLASS_NAME, classData));

      CtClass injected = cp.get(BRAND_CLASS_NAME);
      CtMethod ctm = findTargetMethod(injected);
    } catch (Throwable t) {
      // TODO: Handle properly
      t.printStackTrace();

      throw new HookException(t);
    }
  }
}
