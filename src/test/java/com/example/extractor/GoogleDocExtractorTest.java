package com.example.extractor;

import com.google.api.services.docs.v1.model.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleDocExtractorTest {

    private GoogleDocExtractor extractor;
    private Document mockDocument;

    @BeforeEach
    void setUp() {
        extractor = new GoogleDocExtractor();
        mockDocument = createMockDocumentWithMixedContent();
    }

    private Document createMockDocumentWithMixedContent() {
        Document doc = new Document();
        Body body = new Body();
        
        Map<String, InlineObject> inlineObjects = Map.of(
            "img_id_1", new InlineObject().setInlineObjectProperties(new InlineObjectProperties().setEmbeddedObject(new EmbeddedObject().setImageProperties(new ImageProperties().setContentUri("http://example.com/image1.jpg")))),
            "img_id_2", new InlineObject().setInlineObjectProperties(new InlineObjectProperties().setEmbeddedObject(new EmbeddedObject().setImageProperties(new ImageProperties().setContentUri("http://example.com/image2.jpg"))))
        );
        doc.setInlineObjects(inlineObjects);

        List<StructuralElement> content = List.of(
            new StructuralElement().setParagraph(new Paragraph().setElements(List.of(
                new ParagraphElement().setTextRun(new TextRun().setContent("First paragraph.\n"))
            ))),
            new StructuralElement().setParagraph(new Paragraph()
                .setBullet(new Bullet().setNestingLevel(0))
                .setElements(List.of(
                    new ParagraphElement().setTextRun(new TextRun().setContent("List item one.\n"))
                ))
            ),
            new StructuralElement().setParagraph(new Paragraph()
                .setBullet(new Bullet().setNestingLevel(0))
                .setElements(List.of(
                    new ParagraphElement().setTextRun(new TextRun().setContent("List item with image: ")),
                    new ParagraphElement().setInlineObjectElement(new InlineObjectElement().setInlineObjectId("img_id_2"))
                ))
            ),
            new StructuralElement().setParagraph(new Paragraph().setElements(List.of(
                new ParagraphElement().setTextRun(new TextRun().setContent("Final paragraph.\n"))
            )))
        );
        body.setContent(content);
        doc.setBody(body);
        return doc;
    }

    @Test
    @DisplayName("Should extract content in the correct order, including images in lists")
    void testExtractionOrder() {
        String jsonString = extractor.extractContentAsJson(mockDocument);
        Gson gson = new Gson();
        JsonArray result = gson.fromJson(jsonString, JsonArray.class);

        assertEquals(4, result.size(), "Should be 4 top-level elements");
        assertEquals("paragraph", result.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("listItem", result.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("listItem", result.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("paragraph", result.get(3).getAsJsonObject().get("type").getAsString());

        JsonObject listItemWithImage = result.get(2).getAsJsonObject();
        JsonArray contentOfListItem = listItemWithImage.get("content").getAsJsonArray();
        
        assertEquals(2, contentOfListItem.size(), "List item content should have two parts (text and image)");
        
        JsonObject textPart = contentOfListItem.get(0).getAsJsonObject();
        assertEquals("text", textPart.get("type").getAsString());
        assertEquals("List item with image: ", textPart.get("value").getAsString());
        
        JsonObject imagePart = contentOfListItem.get(1).getAsJsonObject();
        assertEquals("image", imagePart.get("type").getAsString());
        assertEquals("img_id_2", imagePart.get("objectId").getAsString());
        assertEquals("http://example.com/image2.jpg", imagePart.get("url").getAsString());
    }
}
