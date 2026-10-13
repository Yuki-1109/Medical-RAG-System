package com.medicalrag.image;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
public record ImageMetadata(String imageType,String stage,String severity,String keywords,String note) {
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static ImageMetadata parse(JsonNode body) {
        Set<String> fields=Set.of("imageType","stage","severity","keywords","note");
        if (body==null || !body.isObject()) throw invalid();
        body.fieldNames().forEachRemaining(key -> { if (!fields.contains(key) || !body.get(key).isTextual()) throw invalid(); });
        if (!body.path("imageType").isTextual()) throw invalid();
        return new ImageMetadata(body.get("imageType").asText(),body.path("stage").asText(""),body.path("severity").asText(""),body.path("keywords").asText(""),body.path("note").asText("")).normalized();
    }
    public ImageMetadata normalized() {
        if (!Set.of("LESION","NORMAL","UNCLASSIFIED").contains(imageType==null ? "" : imageType)) throw invalid();
        return new ImageMetadata(imageType,text(stage,100),text(severity,100),text(keywords,500),text(note,2000));
    }
    private static String text(String value,int max) {
        String text=value==null ? "" : value.strip();
        // Plain text only; no HTML/script input, even though Vue escapes all output.
        if (text.length()>max || text.contains("<") || text.contains(">") || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')) throw invalid();
        return text;
    }
    private static ImageException invalid() { return new ImageException(400,"INVALID_IMAGE_METADATA","人工信息无效：类型须为病灶、正常对照或未分类；分期/程度最多100字，关键词500字，备注2000字，不接受 HTML、脚本或额外字段"); }
}
