package com.ias.ticket;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PrivateRootTest {
    @Test void explicitRestoreRootMustBeAbsoluteAndIsNormalized() {
        Path absolute=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("ias-restore").normalize();
        assertEquals(absolute,FileStore.privateRoot(absolute.toString()));
        assertThrows(IllegalStateException.class,()->FileStore.privateRoot("relative/restore"));
        assertEquals(Path.of("C:/ias-local/Develop/IAS-MySQL84/private-files/ticket").toAbsolutePath().normalize(),FileStore.privateRoot(null));
    }
}
