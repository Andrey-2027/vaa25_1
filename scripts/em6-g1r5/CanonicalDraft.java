import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.ipro.design.EntityDraft;
import org.ipro.design.json.DraftJsonCodec;

/** Converts the generator-test record JSON to the strict public P8 transport through its codec. */
class CanonicalDraft {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("raw-draft.json public-draft.json");
        var raw=new ObjectMapper().readValue(Path.of(args[0]).toFile(),EntityDraft.class);
        var codec=new DraftJsonCodec();
        var bytes=codec.encode(raw);
        if (!codec.decode(bytes).equals(raw)) throw new IllegalStateException("Codec round trip changed the draft");
        Files.write(Path.of(args[1]),bytes);
    }
}
