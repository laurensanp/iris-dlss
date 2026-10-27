import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Copies the Vitrail jar, dropping the @Redirect from VulkanCommandEncoderMixin.vitrail$afterPass (Lunar conflict). */
public class PatchVitrail {
    static final String ENTRY = "dev/vitrail/mixin/VulkanCommandEncoderMixin.class";

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]), out = Path.of(args[1]);
        int removed = 0;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(in));
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                if (e.getName().equals(ENTRY)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(data).accept(node, 0);
                    for (MethodNode m : node.methods) {
                        if (!m.name.equals("vitrail$afterPass")) continue;
                        for (List<AnnotationNode> list : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
                            if (list != null && list.removeIf(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))) {
                                removed++;
                            }
                        }
                    }
                    ClassWriter writer = new ClassWriter(0);
                    node.accept(writer);
                    data = writer.toByteArray();
                }
                ZipEntry copy = new ZipEntry(e.getName());
                copy.setTime(e.getTime());
                zout.putNextEntry(copy);
                zout.write(data);
                zout.closeEntry();
            }
        }
        if (removed != 1) {
            Files.deleteIfExists(out);
            throw new IllegalStateException("expected one @Redirect on vitrail$afterPass, removed " + removed);
        }
        System.out.println("patched: " + out);
    }
}
