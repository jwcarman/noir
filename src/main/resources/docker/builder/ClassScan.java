// noir measurement class scan.
// Usage: java ClassScan.java <classesDir> <changed.txt> <generatedOut> <targetsOut>
//
// Reads the class files the builder compiled (never loads them) and, for every class compiled from
// a changed main source (package directory and SourceFile attribute match a src/main/java path in
// changed.txt), writes:
//   <targetsOut>   its binary name (javap spelling, e.g. a.b.Outer$Inner): PIT's target classes;
//   <generatedOut> its binary name if the class or one of its methods carries an annotation whose
//                  simple name contains "Generated" (JaCoCo silently drops such code from coverage).
// Fails closed: an unreadable class file in a changed package becomes a line in <generatedOut>.
// Both files are always written when the exit code is 0.
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class ClassScan {
  static final Pattern SOURCE = Pattern.compile("src/main/java/(?:(.+)/)?([^/]+\\.java)");
  static final long MAX_CLASS_BYTES = 16L * 1024 * 1024;

  public static void main(String[] args) throws IOException {
    var classes = Path.of(args[0]);
    var changed = new HashSet<String>(); // "a/b|C.java"
    var packages = new HashSet<String>(); // "a/b"
    for (var line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
      var m = SOURCE.matcher(line.strip());
      if (m.matches()) {
        var pkg = m.group(1) == null ? "" : m.group(1);
        changed.add(pkg + "|" + m.group(2));
        packages.add(pkg);
      }
    }
    var generated = new TreeSet<String>();
    var targets = new TreeSet<String>();
    for (var pkg : packages) {
      var dir = pkg.isEmpty() ? classes : classes.resolve(pkg);
      if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      List<Path> files;
      try (var list = Files.list(dir)) {
        files = list.filter(p -> p.getFileName().toString().endsWith(".class")).sorted().toList();
      }
      for (var file : files) {
        var rel = (pkg.isEmpty() ? "" : pkg + "/") + file.getFileName();
        Info info;
        try {
          info = read(file);
        } catch (IOException | RuntimeException e) {
          generated.add("unreadable class file: " + rel);
          continue;
        }
        int slash = info.name.lastIndexOf('/');
        var classPkg = slash < 0 ? "" : info.name.substring(0, slash);
        if (!classPkg.equals(pkg) || !changed.contains(pkg + "|" + info.sourceFile)) {
          continue;
        }
        var binary = info.name.replace('/', '.');
        targets.add(binary);
        if (info.generated) {
          generated.add(binary);
        }
      }
    }
    Files.write(Path.of(args[2]), generated, StandardCharsets.UTF_8);
    Files.write(Path.of(args[3]), targets, StandardCharsets.UTF_8);
  }

  record Info(String name, String sourceFile, boolean generated) {}

  static Info read(Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.size(file) > MAX_CLASS_BYTES) {
      throw new IOException("not a regular class file");
    }
    var in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)));
    if (in.readInt() != 0xCAFEBABE) {
      throw new IOException("bad magic");
    }
    in.readUnsignedShort();
    in.readUnsignedShort();
    int count = in.readUnsignedShort();
    var utf8 = new String[count];
    var classIndex = new int[count];
    for (int i = 1; i < count; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> utf8[i] = in.readUTF();
        case 7 -> classIndex[i] = in.readUnsignedShort();
        case 8, 16, 19, 20 -> in.readUnsignedShort();
        case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
        case 15 -> {
          in.readUnsignedByte();
          in.readUnsignedShort();
        }
        case 5, 6 -> {
          in.readLong();
          i++;
        }
        default -> throw new IOException("bad constant pool tag " + tag);
      }
    }
    in.readUnsignedShort(); // access flags
    var name = utf8[classIndex[in.readUnsignedShort()]];
    if (name == null) {
      throw new IOException("bad this_class");
    }
    in.readUnsignedShort(); // super
    int interfaces = in.readUnsignedShort();
    for (int i = 0; i < interfaces; i++) {
      in.readUnsignedShort();
    }
    var state = new boolean[1];
    var sourceFile = new String[1];
    for (int member = 0; member < 2; member++) { // fields, then methods
      int n = in.readUnsignedShort();
      for (int i = 0; i < n; i++) {
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        attributes(in, utf8, state, null, member == 1);
      }
    }
    attributes(in, utf8, state, sourceFile, true);
    return new Info(name, sourceFile[0], state[0]);
  }

  /** Reads an attribute table, recording SourceFile and (when {@code check}) Generated annotations. */
  static void attributes(
      DataInputStream in, String[] utf8, boolean[] generated, String[] sourceFile, boolean check)
      throws IOException {
    int n = in.readUnsignedShort();
    for (int i = 0; i < n; i++) {
      var attr = utf8[in.readUnsignedShort()];
      var body = new byte[in.readInt()];
      in.readFully(body);
      var data = new DataInputStream(new ByteArrayInputStream(body));
      if (sourceFile != null && "SourceFile".equals(attr)) {
        sourceFile[0] = utf8[data.readUnsignedShort()];
      } else if (check
          && ("RuntimeVisibleAnnotations".equals(attr)
              || "RuntimeInvisibleAnnotations".equals(attr))) {
        int count = data.readUnsignedShort();
        for (int a = 0; a < count; a++) {
          if (annotation(data, utf8)) {
            generated[0] = true;
          }
        }
      }
    }
  }

  /** Skips one annotation; true if its type's simple name contains "Generated". */
  static boolean annotation(DataInputStream in, String[] utf8) throws IOException {
    var type = utf8[in.readUnsignedShort()];
    int pairs = in.readUnsignedShort();
    for (int i = 0; i < pairs; i++) {
      in.readUnsignedShort();
      elementValue(in, utf8);
    }
    if (type == null) {
      throw new IOException("bad annotation type");
    }
    var simple = type.substring(Math.max(type.lastIndexOf('/'), type.lastIndexOf('$')) + 1);
    return simple.contains("Generated");
  }

  static void elementValue(DataInputStream in, String[] utf8) throws IOException {
    int tag = in.readUnsignedByte();
    switch (tag) {
      case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> in.readUnsignedShort();
      case 'e' -> in.readInt();
      case '@' -> annotation(in, utf8);
      case '[' -> {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
          elementValue(in, utf8);
        }
      }
      default -> throw new IOException("bad element value tag " + tag);
    }
  }
}
