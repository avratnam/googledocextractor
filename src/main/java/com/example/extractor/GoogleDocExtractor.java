package com.example.extractor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL; // <-- ADDED IMPORT
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.api.services.docs.v1.model.Dimension;
import com.google.api.services.docs.v1.model.Document;
import com.google.api.services.docs.v1.model.EmbeddedObject;
import com.google.api.services.docs.v1.model.InlineObject;
import com.google.api.services.docs.v1.model.Link;
import com.google.api.services.docs.v1.model.Paragraph;
import com.google.api.services.docs.v1.model.ParagraphElement;
import com.google.api.services.docs.v1.model.ParagraphStyle;
import com.google.api.services.docs.v1.model.RgbColor;
import com.google.api.services.docs.v1.model.Size;
import com.google.api.services.docs.v1.model.StructuralElement;
import com.google.api.services.docs.v1.model.Table;
import com.google.api.services.docs.v1.model.TableCell;
import com.google.api.services.docs.v1.model.TableRow;
import com.google.api.services.docs.v1.model.TextStyle;
import com.google.api.services.docs.v1.model.WeightedFontFamily;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class GoogleDocExtractor {

    private final S3Client s3Client;
    private final String s3BucketName;

    public GoogleDocExtractor(S3Client s3Client, String s3BucketName) {
        this.s3Client = s3Client;
        this.s3BucketName = s3BucketName;
    }

    private static class ImageInfo {
        final String objectId;
        final String contentUri;
        final String contentType;
        ImageInfo(String objectId, String contentUri, String contentType) {
            this.objectId = objectId;
            this.contentUri = contentUri;
            this.contentType = contentType;
        }
    }

    private static class ProcessingContext {
        final String documentId;
        final String topicSlug;
        final Map<String, InlineObject> inlineObjectsMap;
        final AtomicInteger imageCounter = new AtomicInteger(0);
        String firstImageUrl = null;
        ProcessingContext(String documentId, String topicSlug, Map<String, InlineObject> inlineObjectsMap) {
            this.documentId = documentId;
            this.topicSlug = topicSlug;
            this.inlineObjectsMap = inlineObjectsMap;
        }
    }
    
    private static class IntroductionExtractionResult {
        final String text;
        final List<Integer> indicesToRemove;
        IntroductionExtractionResult(String text, List<Integer> indicesToRemove) {
            this.text = text;
            this.indicesToRemove = indicesToRemove;
        }
    }

    public void downloadAndUploadImagesToS3(Document document) {
        if (document.getBody() == null || document.getBody().getContent() == null) return;
        String topicSlug = slugifyTitle(document.getTitle());
        String documentId = document.getDocumentId();
        List<ImageInfo> imagesToProcess = new ArrayList<>();
        collectImagesInOrder(document.getBody().getContent(), document.getInlineObjects(), imagesToProcess);
        
        System.out.printf("Found %d images to process for document: %s\n", imagesToProcess.size(), document.getTitle());
        for (int i = 0; i < imagesToProcess.size(); i++) {
            ImageInfo imageInfo = imagesToProcess.get(i);
            String imageName = String.format("image_%03d.jpg", i + 1);
            String s3Key = String.format("%s/%s/%s", topicSlug, documentId, imageName);
            System.out.printf("Processing image %d: %s\n", (i + 1), s3Key);
            try (InputStream imageStream = new URL(imageInfo.contentUri).openStream()) {
                byte[] imageBytes = imageStream.readAllBytes();
                PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(this.s3BucketName)
                    .key(s3Key)
                    .contentType(imageInfo.contentType)
                    .build();
                s3Client.putObject(request, RequestBody.fromBytes(imageBytes));
                System.out.printf("Successfully uploaded to s3://%s/%s\n", this.s3BucketName, s3Key);
            } catch (IOException | S3Exception e) {
                System.err.printf("Failed to process image %s. Error: %s\n", s3Key, e.getMessage());
            }
        }
    }

    private void collectImagesInOrder(List<StructuralElement> elements, Map<String, InlineObject> inlineObjectsMap, List<ImageInfo> imageList) {
        if (elements == null) return;
        for (StructuralElement structuralElement : elements) {
            if (structuralElement.getParagraph() != null) {
                for (ParagraphElement paraElement : structuralElement.getParagraph().getElements()) {
                    if (paraElement.getInlineObjectElement() != null) {
                        String objectId = paraElement.getInlineObjectElement().getInlineObjectId();
                        if (objectId != null && inlineObjectsMap != null && inlineObjectsMap.containsKey(objectId)) {
                            InlineObject inlineObject = inlineObjectsMap.get(objectId);
                            if (inlineObject.getInlineObjectProperties() != null && inlineObject.getInlineObjectProperties().getEmbeddedObject() != null && inlineObject.getInlineObjectProperties().getEmbeddedObject().getImageProperties() != null) {
                                String contentUri = inlineObject.getInlineObjectProperties().getEmbeddedObject().getImageProperties().getContentUri();
                                if (contentUri != null && !contentUri.isEmpty()) {
                                    imageList.add(new ImageInfo(objectId, contentUri, "image/jpeg"));
                                }
                            }
                        }
                    }
                }
            } else if (structuralElement.getTable() != null) {
                for (TableRow row : structuralElement.getTable().getTableRows()) {
                    for (TableCell cell : row.getTableCells()) {
                        collectImagesInOrder(cell.getContent(), inlineObjectsMap, imageList);
                    }
                }
            }
        }
    }

    public String extractContentAsJson(Document document) {
        JsonObject rootObject = new JsonObject();
        List<StructuralElement> structuralElements = (document.getBody() != null) ? document.getBody().getContent() : null;
        String title = document.getTitle();
        String documentId = document.getDocumentId();
        
        IntroductionExtractionResult introResult = extractIntroductionAndGetIndicesToRemove(structuralElements);
        String topicSlug = slugifyTitle(title);
        ProcessingContext context = new ProcessingContext(documentId, topicSlug, document.getInlineObjects());
        JsonArray documentContentArray = processStructuralElements(structuralElements, context, introResult.indicesToRemove);

        String processedTitle = title;
        final String suffixToRemove = " - Completed";
        if (processedTitle != null && processedTitle.endsWith(suffixToRemove)) {
            processedTitle = processedTitle.substring(0, processedTitle.length() - suffixToRemove.length());
        }

        rootObject.addProperty("article_title", processedTitle != null ? processedTitle : "");
        rootObject.addProperty("article_info", introResult.text != null ? introResult.text : ".");
        rootObject.addProperty("article_image", context.firstImageUrl != null ? context.firstImageUrl : "");
        rootObject.add("document", documentContentArray);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(rootObject);
    }
    
    private IntroductionExtractionResult extractIntroductionAndGetIndicesToRemove(List<StructuralElement> elements) {
        if (elements == null) {
            return new IntroductionExtractionResult(null, Collections.emptyList());
        }
        List<Integer> indicesToRemove = new ArrayList<>();
        String introText = null;
        for (int i = 0; i < elements.size(); i++) {
            StructuralElement element = elements.get(i);
            if (element.getParagraph() != null) {
                Paragraph p = element.getParagraph();
                if (p.getParagraphStyle() != null && "HEADING_1".equals(p.getParagraphStyle().getNamedStyleType())) {
                    String text = extractTextFromParagraph(p);
                    if ("Introduction".equalsIgnoreCase(text.trim())) {
                        indicesToRemove.add(i);
                        if (i + 1 < elements.size() && elements.get(i + 1).getParagraph() != null) {
                            introText = extractTextFromParagraph(elements.get(i + 1).getParagraph());
                            indicesToRemove.add(i + 1);
                        }
                        break;
                    }
                }
            }
        }
        return new IntroductionExtractionResult(introText, indicesToRemove);
    }

    private JsonArray processStructuralElements(List<StructuralElement> elements, ProcessingContext context, List<Integer> indicesToSkip) {
        JsonArray contentArray = new JsonArray();
        if (elements == null) return contentArray;

        boolean inReferencesSection = false;
        StringBuilder referencesTextBuilder = new StringBuilder();

        for (int i = 0; i < elements.size(); i++) {
            if (indicesToSkip.contains(i)) continue;

            StructuralElement structuralElement = elements.get(i);
            Paragraph paragraph = structuralElement.getParagraph();

            if (!inReferencesSection) {
                if (paragraph != null) {
                    if (paragraph.getParagraphStyle() != null && "HEADING_1".equals(paragraph.getParagraphStyle().getNamedStyleType())) {
                        String text = extractTextFromParagraph(paragraph);
                        if ("References".equalsIgnoreCase(text.trim())) {
                            inReferencesSection = true;
                        }
                    }
                    JsonObject processedParagraph = processParagraph(paragraph, context);
                    if (processedParagraph != null) {
                        contentArray.add(processedParagraph);
                    }
                } else if (structuralElement.getTable() != null) {
                    contentArray.add(processTable(structuralElement.getTable(), context));
                }
            } else {
                if (paragraph != null) {
                    referencesTextBuilder.append(getRawParagraphContent(paragraph)).append("\n");
                }
            }
        }

        if (referencesTextBuilder.length() > 0) {
            JsonObject referencesParaJson = new JsonObject();
            referencesParaJson.addProperty("type", "paragraph");
            referencesParaJson.addProperty("styleType", "NORMAL_TEXT");
            JsonArray referencesContentArray = new JsonArray();
            JsonObject textObject = new JsonObject();
            textObject.addProperty("type", "text");
            String rawReferences = referencesTextBuilder.toString();
            String cleanedReferences = rawReferences.replaceAll("[\\n\\u000B]+", "\n").trim();
            textObject.addProperty("value", cleanedReferences);
            referencesContentArray.add(textObject);
            referencesParaJson.add("content", referencesContentArray);
            contentArray.add(referencesParaJson);
        }

        return contentArray;
    }

    private JsonObject processTable(Table table, ProcessingContext context) {
        JsonObject tableJson = new JsonObject();
        tableJson.addProperty("type", "table");
        JsonArray rowsArray = new JsonArray();
        for (TableRow row : table.getTableRows()) {
            JsonObject rowJson = new JsonObject();
            rowJson.addProperty("type", "tableRow");
            JsonArray cellsArray = new JsonArray();
            for (TableCell cell : row.getTableCells()) {
                JsonObject cellJson = new JsonObject();
                cellJson.addProperty("type", "tableCell");
                JsonArray cellContent = processStructuralElements(cell.getContent(), context, Collections.emptyList());
                cellJson.add("content", cellContent);
                cellsArray.add(cellJson);
            }
            rowJson.add("cells", cellsArray);
            rowsArray.add(rowJson);
        }
        tableJson.add("rows", rowsArray);
        return tableJson;
    }

    private JsonObject processParagraph(Paragraph paragraph, ProcessingContext context) {
        JsonObject paragraphJson = new JsonObject();
        JsonArray contentArray = new JsonArray();
        
        ParagraphStyle paragraphStyle = paragraph.getParagraphStyle();
        if (paragraph.getBullet() != null) {
            paragraphJson.addProperty("type", "listItem");
            paragraphJson.addProperty("nestingLevel", paragraph.getBullet().getNestingLevel() != null ? paragraph.getBullet().getNestingLevel() : 0);
        } else {
            paragraphJson.addProperty("type", "paragraph");
        }
        if (paragraphStyle != null) {
            if (paragraphStyle.getNamedStyleType() != null) paragraphJson.addProperty("styleType", paragraphStyle.getNamedStyleType());
            if (paragraphStyle.getAlignment() != null) paragraphJson.addProperty("alignment", paragraphStyle.getAlignment());
        }

        for (ParagraphElement element : paragraph.getElements()) {
            if (element.getTextRun() != null) {
                String text = element.getTextRun().getContent();
                if (text != null && !text.equals("\n")) {
                    JsonObject textJson = new JsonObject();
                    textJson.addProperty("type", "text");
                    textJson.addProperty("value", text);
                    JsonObject styleJson = processTextStyle(element.getTextRun().getTextStyle());
                    if (styleJson.size() > 0) textJson.add("style", styleJson);
                    contentArray.add(textJson);
                }
            } else if (element.getInlineObjectElement() != null) {
                String objectId = element.getInlineObjectElement().getInlineObjectId();
                if (objectId != null && context.inlineObjectsMap != null && context.inlineObjectsMap.containsKey(objectId)) {
                    int imageIndex = context.imageCounter.incrementAndGet();
                    String imageName = String.format("image_%03d", imageIndex);
                    String newImageUrl = String.format("/api/images/%s/%s/%s.jpg", context.topicSlug, context.documentId, imageName);
                    if (imageIndex == 1) {
                        context.firstImageUrl = newImageUrl;
                        continue;
                    }
                    JsonObject imageJson = new JsonObject();
                    imageJson.addProperty("type", "image");
                    imageJson.addProperty("objectId", objectId);
                    imageJson.addProperty("url", newImageUrl);
                    
                    // >> START: CORRECTED LOGIC FOR IMAGE DIMENSIONS <<
                    InlineObject inlineObject = context.inlineObjectsMap.get(objectId);
                    if (inlineObject.getInlineObjectProperties() != null &&
                        inlineObject.getInlineObjectProperties().getEmbeddedObject() != null) {
                        
                        EmbeddedObject embeddedObject = inlineObject.getInlineObjectProperties().getEmbeddedObject();
                        
                        if (embeddedObject.getSize() != null) {
                            Size size = embeddedObject.getSize();
                            Dimension width = size.getWidth();
                            if (width != null && width.getMagnitude() != null) {
                                imageJson.addProperty("width", width.getMagnitude());
                            }
                            Dimension height = size.getHeight();
                            if (height != null && height.getMagnitude() != null) {
                                imageJson.addProperty("height", height.getMagnitude());
                            }
                        }
                    }
                    // >> END: CORRECTED LOGIC FOR IMAGE DIMENSIONS <<

                    contentArray.add(imageJson);
                }
            }
        }
        
        if (contentArray.isEmpty()) return null;
        paragraphJson.add("content", contentArray);
        return paragraphJson;
    }
    
    private String extractTextFromParagraph(Paragraph paragraph) {
        if (paragraph == null || paragraph.getElements() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ParagraphElement element : paragraph.getElements()) {
            if (element.getTextRun() != null && element.getTextRun().getContent() != null) {
                sb.append(element.getTextRun().getContent());
            }
        }
        return sb.toString().replaceAll("[\\n\\u000B]", " ").trim();
    }

    private String getRawParagraphContent(Paragraph paragraph) {
        if (paragraph == null || paragraph.getElements() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ParagraphElement element : paragraph.getElements()) {
            if (element.getTextRun() != null && element.getTextRun().getContent() != null) {
                sb.append(element.getTextRun().getContent());
            }
        }
        return sb.toString();
    }

    private JsonObject processTextStyle(TextStyle textStyle) {
        JsonObject styleJson = new JsonObject();
        if (textStyle == null) return styleJson;
        if (Boolean.TRUE.equals(textStyle.getBold())) styleJson.addProperty("bold", true);
        if (Boolean.TRUE.equals(textStyle.getItalic())) styleJson.addProperty("italic", true);
        if (Boolean.TRUE.equals(textStyle.getUnderline())) styleJson.addProperty("underline", true);
        if (Boolean.TRUE.equals(textStyle.getStrikethrough())) styleJson.addProperty("strikethrough", true);
        Link link = textStyle.getLink();
        if (link != null && link.getUrl() != null) {
            styleJson.addProperty("linkUrl", link.getUrl());
        }
        WeightedFontFamily fontFamily = textStyle.getWeightedFontFamily();
        if (fontFamily != null && fontFamily.getFontFamily() != null) {
            styleJson.addProperty("fontFamily", fontFamily.getFontFamily());
        }
        return styleJson;
    }

    private String formatRgbColor(RgbColor rgbColor) {
        if (rgbColor == null) return null;
        float rFloat = rgbColor.getRed() == null ? 0f : rgbColor.getRed();
        float gFloat = rgbColor.getGreen() == null ? 0f : rgbColor.getGreen();
        float bFloat = rgbColor.getBlue() == null ? 0f : rgbColor.getBlue();
        int r = (int) (rFloat * 255);
        int g = (int) (gFloat * 255);
        int b = (int) (bFloat * 255);
        return String.format("#%02x%02x%02x", r, g, b);
    }
    
    private String slugifyTitle(String title) {
        if (title == null || title.isEmpty()) return "";
        String processedTitle = title.toLowerCase();
        final String suffixToRemove = " - completed";
        if (processedTitle.endsWith(suffixToRemove)) {
            processedTitle = processedTitle.substring(0, processedTitle.length() - suffixToRemove.length());
        }
        return processedTitle.replaceAll("[^a-z0-9]", "");
    }
}