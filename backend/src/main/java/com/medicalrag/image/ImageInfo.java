package com.medicalrag.image;
import java.time.Instant;
/** Public metadata only; original pixels and EXIF are never changed by editing. */
public record ImageInfo(String id,String knowledgeBaseId,String originalFilename,String fileFormat,Long fileSize,
        String sha256,Integer width,Integer height,String imageType,String stage,String severity,String keywords,String note,
        String lifecycleStatus,String cleanupError,Instant createdAt,Instant updatedAt) {}
