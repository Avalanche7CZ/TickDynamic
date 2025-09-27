package com.wildex999.patcher;

import java.io.*;
import org.apache.commons.io.IOUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.TraceClassVisitor;
import net.minecraft.launchwrapper.IClassTransformer;

public class TransformerPatcher implements IClassTransformer {
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        InputStream input = getClass().getResourceAsStream("/patches/" + transformedName.replace('.', '/') + ".patch");
        if(input != null) {
            try {
                System.out.println("Patching class: " + transformedName);
                Writer stringWriter = new StringWriter();
                TraceClassVisitor printer = new TraceClassVisitor(null, new ExtraTextifier(), new PrintWriter(stringWriter));
                ClassReader cr = new ClassReader(basicClass);
                cr.accept(printer, ClassReader.EXPAND_FRAMES);
                String baseData = stringWriter.toString();
                String patch = IOUtils.toString(input);
                PatchParser patchParser = new PatchParser();
                patchParser.parsePatch(patch);
                String patchedData = patchParser.patch(baseData);
                if(patchedData == null)
                    throw new RuntimeException("Failed to patch class: " + name + ".\nThis usually means there is either a mod conflict or patch version is wrong!");
                ASMClassParser parser = new ASMClassParser();
                ClassWriter parsedClass = parser.parseClass(patchedData);
                basicClass = parsedClass.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return basicClass;
    }
}
