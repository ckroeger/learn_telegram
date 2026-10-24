package telegram.lernen.dateien;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class UebungLoesungTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void beschreibtDieGroessteGroesse() throws Exception {
        var groessen = JSON.readTree("[{\"width\":90,\"height\":60,\"file_size\":1000},"
                + "{\"width\":1280,\"height\":720,\"file_size\":87040},{\"width\":320,\"height\":180,\"file_size\":9000}]");

        assertEquals("Foto 1280x720, 85 KB (größte von 3 Größen)", UebungLoesung.fotoBeschreibung(groessen));
    }

    @Test
    void ohneGroessenGibtEsKeinFoto() throws Exception {
        assertEquals("Kein Foto", UebungLoesung.fotoBeschreibung(JSON.readTree("[]")));
    }
}
