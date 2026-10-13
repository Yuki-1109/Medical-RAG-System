package com.medicalrag.image;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("app.image")
public record ImageProperties(int maxWidth,int maxHeight,long maxPixels) {
    public ImageProperties { if (maxWidth<=0 || maxHeight<=0 || maxPixels<=0) throw new IllegalArgumentException("图片尺寸与像素上限必须为正整数"); }
}
