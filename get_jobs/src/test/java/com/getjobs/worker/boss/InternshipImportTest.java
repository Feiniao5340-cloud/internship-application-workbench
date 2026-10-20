package com.getjobs.worker.boss;

import com.getjobs.application.controller.InternshipController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import java.nio.file.Path;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class InternshipImportTest {
    @TempDir Path directory;
    InternshipController controller(){return new InternshipController(new InternshipStore(directory),null);}
    @Test void utf8ResumeIsReadWithoutSavingCandidateOrCallingModel() throws Exception {
        var result=controller().resume(new MockMultipartFile("file","resume.txt","text/plain","虚构简历：需求分析。".getBytes(StandardCharsets.UTF_8)));
        assertTrue(result.get("text").contains("需求分析"));assertFalse(java.nio.file.Files.exists(directory.resolve("settings.json")));
    }
    @Test void docxKeepsParagraphBoundaries() throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document xmlns:w='urn:test'><w:body><w:p><w:r><w:t>项目一</w:t></w:r></w:p><w:p><w:r><w:t>项目二</w:t></w:r></w:p></w:body></w:document>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertEquals("项目一\n项目二\n",controller().resume(new MockMultipartFile("file","resume.docx","application/octet-stream",bytes.toByteArray())).get("text"));
    }
    @Test void pdfTextIsExtractedLocally() throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var document=new PDDocument()) {
            var page=new PDPage();document.addPage(page);
            try(var stream=new PDPageContentStream(document,page)) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(30,700);stream.showText("Fictional internship resume");stream.endText();
            }
            document.save(bytes);
        }
        assertTrue(controller().resume(new MockMultipartFile("file","resume.pdf","application/pdf",bytes.toByteArray())).get("text").contains("Fictional internship resume"));
    }
    @Test void unsupportedImageImportIsRejected() {
        assertThrows(IllegalArgumentException.class,()->controller().resume(new MockMultipartFile("file","resume.png","image/png",new byte[]{1,2,3})));
    }
}
