package com.getjobs.worker.boss;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PortableModelApiTest {
    @Test void supportsBothExplicitApiSurfaces(){assertEquals("https://example.test/v1/chat/completions",PortableModelApi.endpoint("https://example.test/v1/"));assertEquals("https://example.test/v1/responses",PortableModelApi.endpoint("https://example.test/v1/responses"));assertEquals("http://127.0.0.1:1234/v1/chat/completions",PortableModelApi.endpoint("http://127.0.0.1:1234/v1"));}
    @Test void disallowsPlainRemoteHttpAndEmbeddedCredentials(){for(String base:new String[]{"http://example.test/v1","https://secret@example.test/v1","https://example.test/v1?key=secret"})assertThrows(IllegalArgumentException.class,()->PortableModelApi.endpoint(base));}
    @Test void parsesChatAndResponsesWithoutExposingProviderMetadata(){assertEquals("{}",PortableModelApi.parse("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"));assertEquals("{}",PortableModelApi.parse("{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}"));assertThrows(IllegalStateException.class,()->PortableModelApi.parse("{}"));}
}
