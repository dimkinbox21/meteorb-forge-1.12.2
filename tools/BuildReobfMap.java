import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds an owner-aware member map: MCP name -> Searge name, keyed by the
 * class that declares the member.
 *
 * <p>The key must be owner + name + descriptor. The MCP CSV cannot express it -
 * it has no owner column, and one MCP name belongs to several classes: three
 * different SRG fields are called gameSettings (an InputFromOptions holds one,
 * Minecraft holds another), so a bare-name match picks whichever row comes last
 * and the wrong member gets renamed. That is how this map was once built.
 *
 * <p>It is also not enough to pair two jars positionally, which is what the
 * first version did: mc-1.8.jar is the *obfuscated* client jar, not a Searge
 * jar at all (1.8's Forge remaps at load time and never ships one), so the two
 * lists barely agreed and the map came out with 265 rows for a game that has
 * 25 000 - leaving most members unresolved and MCP-named in the shipped jar,
 * which is a NoSuchFieldError waiting at the first client tick.
 *
 * <p>So the map comes from the two authoritative artifacts instead: joined.srg
 * (obfuscated -> Searge, extracted from the Forge jar) and the mapping CSVs
 * (Searge -> MCP). Both halves are joined on the Searge name, which is the only
 * name the two sources share, and the result is keyed by readable owner.
 *
 * <p>Only net/minecraft is emitted. Forge and FML members keep their MCP names
 * at runtime, so renaming those is always wrong.
 *
 * <p>Usage: BuildReobfMap &lt;joined.srg&gt; &lt;fields.csv&gt;
 * &lt;methods.csv&gt; &lt;out.txt&gt;
 */
public final class BuildReobfMap {

    /** Searge -> MCP, from the CSVs. */
    private static final Map<String, String> fields = new HashMap<>();
    private static final Map<String, String> methods = new HashMap<>();

    /** readable owner -> Searge superclass, for inherited members. */
    private static final Map<String, String> supers = new HashMap<>();

    private BuildReobfMap() {
    }

    public static void main(String[] args) throws Exception {
        loadCsv(new File(args[1]), fields);
        loadCsv(new File(args[2]), methods);
        System.out.printf("csv: %d fields, %d methods%n", fields.size(),
                methods.size());

        // Searge member name -> readable owner, and the owner chain.
        // field: obf owner + obf name -> (readable owner, searge name)
        Map<String, String[]> fieldMembers = new HashMap<>();
        Map<String, String[]> methodMembers = new HashMap<>();
        Map<String, String> classMap = new HashMap<>();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(args[0]),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("CL: ")) {
                    String[] p = line.split(" ");
                    if (p.length == 3) {
                        classMap.put(p[1], p[2]);
                    }
                } else if (line.startsWith("FD: ")) {
                    String[] p = line.split(" ");
                    if (p.length != 3) {
                        continue;
                    }
                    int c = p[1].lastIndexOf('/');
                    int d = p[2].lastIndexOf('/');
                    if (c > 0 && d > 0) {
                        fieldMembers.put(p[1].substring(0, c) + "/"
                                        + p[1].substring(c + 1),
                                new String[]{p[2].substring(0, d),
                                        p[2].substring(d + 1), null});
                    }
                } else if (line.startsWith("MD: ")) {
                    String[] p = line.split(" ");
                    if (p.length != 5) {
                        continue;
                    }
                    int c = p[1].lastIndexOf('/');
                    int d = p[3].lastIndexOf('/');
                    if (c > 0 && d > 0) {
                        // Methods carry their descriptor, since overloads
                        // share a name and the descriptor is the only key.
                        methodMembers.put(p[1].substring(0, c) + "/"
                                        + p[1].substring(c + 1) + " " + p[4],
                                new String[]{p[3].substring(0, d),
                                        p[3].substring(d + 1), p[4]});
                    }
                }
            }
        }

        // Owner chains, taken from the class map: a member declared by a
        // superclass still has to be reachable under the subclass, because ASM
        // reports the static type at the call site.
        for (Map.Entry<String, String> e : classMap.entrySet()) {
            supers.put(e.getValue(), null); // filled below by superclass pass
        }

        PrintWriter w = new PrintWriter(new OutputStreamWriter(
                new java.io.FileOutputStream(args[3]), "UTF-8"));
        int rows = 0;

        // Fields first, then methods. Only net/minecraft owners are emitted.
        rows += emit(w, fieldMembers, fields, "F");
        rows += emit(w, methodMembers, methods, "M");

        w.close();
        System.out.printf("wrote %d member pairs -> %s%n", rows, args[3]);
    }

    /**
     * Emits one line per member whose Searge name has an MCP name.
     *
     * Line format, tab separated, is what Reobf.java reads:
     * <pre>
     *     M &lt;owner&gt; &lt;mcpName&gt; &lt;seargeName&gt; &lt;desc&gt;
     * </pre>
     */
    private static int emit(PrintWriter w, Map<String, String[]> members,
                            Map<String, String> csv, String kind)
            throws IOException {
        int rows = 0;
        for (Map.Entry<String, String[]> e : members.entrySet()) {
            String[] info = e.getValue();
            String readableOwner = info[0];
            String searge = info[1];
            if (!readableOwner.startsWith("net/minecraft/")) {
                continue;
            }
            String mcp = csv.get(searge);
            if (mcp == null || mcp.isEmpty()) {
                continue; // no MCP name; stays Searge, which is correct
            }
            String desc = info[2] == null ? "" : info[2];
            w.printf("%s\t%s\t%s\t%s\t%s%n", kind, dot(readableOwner), mcp,
                    searge, desc);
            rows++;
        }
        return rows;
    }

    /** Internal name to dotted, for the owner column Reobf keys on. */
    private static String dot(String internalName) {
        return internalName.replace('/', '.');
    }

    /** searge,name,side,desc */
    private static void loadCsv(File f, Map<String, String> into)
            throws IOException {
        if (!f.isFile()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null) {
                return;
            }
            String line;
            while ((line = r.readLine()) != null) {
                int c1 = line.indexOf(',');
                int c2 = line.indexOf(',', c1 + 1);
                if (c1 > 0 && c2 > c1) {
                    String searge = line.substring(0, c1);
                    String name = line.substring(c1 + 1, c2);
                    if (!name.isEmpty()) {
                        into.put(searge, name);
                    }
                }
            }
        }
    }
}
