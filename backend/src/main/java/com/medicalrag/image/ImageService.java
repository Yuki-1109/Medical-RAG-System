package com.medicalrag.image;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Semaphore;
import com.medicalrag.knowledgebase.*;
import com.medicalrag.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@org.springframework.core.annotation.Order(10)
@Service
public class ImageService implements KnowledgeBaseMaterialCleaner {
    private final ImageRepository images;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseMaterials materials;
    private final KnowledgeBaseFileStorage files;
    private final ImageValidator validator;
    private final TransactionTemplate transactions;
    private final Semaphore uploads=new Semaphore(2);
    public ImageService(ImageRepository images, KnowledgeBaseRepository bases, KnowledgeBaseMaterials materials,
            KnowledgeBaseFileStorage files, ImageValidator validator, PlatformTransactionManager manager) {
        this.images=images; this.bases=bases; this.materials=materials; this.files=files; this.validator=validator;
        transactions=new TransactionTemplate(manager);
    }
    static long parseId(String value) {
        try { if (value==null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new ImageException(400,"INVALID_IMAGE_ID","图片 ID 格式无效，请从图片列表选择"); }
    }
    static String filename(String value) {
        if (value==null || value.isBlank() || value.length()>255 || value.contains("/") || value.contains("\\") || value.contains(":")
                || value.codePoints().anyMatch(Character::isISOControl)) throw ImageException.invalid();
        String name=value.strip(); format(name); return name;
    }
    static OriginalType format(String name) {
        String lower=name.toLowerCase(java.util.Locale.ROOT);
        if ((lower.endsWith(".jpg") && lower.length()>4) || (lower.endsWith(".jpeg") && lower.length()>5)) return OriginalType.JPEG;
        if (lower.endsWith(".png") && lower.length()>4) return OriginalType.PNG;
        throw new ImageException(400,"INVALID_IMAGE_FILENAME","请选择名称有效的 JPG、JPEG 或 PNG 文件，不支持路径或其他格式");
    }
    public ImageInfo update(long id,long owner,ImageMetadata input) {
        ImageMetadata normalized=input.normalized();
        return transactions.execute(tx -> {
            ImageRecord first=record(id,owner,true); requireAvailable(lockedBase(first.baseId(),owner));
            record(id,owner,true); return images.update(id,normalized).info();
        });
    }
    private KnowledgeBase lockedBase(long id,long owner) {
        materials.requireSupportedStage();
        return bases.find(id,owner,true).orElseThrow(KnowledgeBaseException::missing);
    }
    private void requireAvailable(KnowledgeBase base) {
        if (base.deletionPending()) throw new ImageException(409,"KNOWLEDGE_BASE_DELETING","知识库删除未完成，请重试删除，不能上传或读取资料");
    }
    public List<ImageInfo> list(long base,long owner) {
        materials.requireSupportedStage(); bases.find(base,owner,false).orElseThrow(KnowledgeBaseException::missing);
        return images.list(base,owner).stream().map(ImageRecord::info).toList();
    }
    ImageRecord record(long id,long owner,boolean active) {
        ImageRecord record=images.find(id,owner).orElseThrow(ImageException::missing);
        KnowledgeBase base=bases.find(record.baseId(),owner,false).orElseThrow(ImageException::missing);
        if (active && (base.deletionPending() || !record.info().lifecycleStatus().equals("ACTIVE"))) throw ImageException.missing();
        return record;
    }
    public ImageInfo detail(long id,long owner) { return record(id,owner,true).info(); }
    public ImageInfo upload(long base,long owner,String name,InputStream source) {
        name=filename(name);
        if (!uploads.tryAcquire()) throw new ImageException(429,"UPLOAD_BUSY","当前已有上传任务，请等待完成后重试");
        ImageRecord intent=null;
        try {
            String normalized=name;
            // Persist the server-generated path BEFORE file I/O, so even a crash or DB
            // activation failure has a retryable cleanup record. This is not an active image.
            intent=transactions.execute(tx -> {
                requireAvailable(lockedBase(base,owner));
                return images.reserve(base,normalized,files.reserve(Long.toString(base),format(normalized)));
            });
            ImageRecord reserved=intent;
            return transactions.execute(tx -> {
                requireAvailable(lockedBase(base,owner));
                ImageRecord current=record(reserved.id(),owner,false);
                if (!current.info().lifecycleStatus().equals("UPLOADING")) throw ImageException.missing();
                StoredOriginal receipt=files.saveReserved(Long.toString(base),current.reservation(),normalized,source);
                ImageValidator.Dimensions dimensions=files.inspect(Long.toString(base),receipt,channel -> validator.validate(channel,receipt.type()));
                return images.activate(current.id(),receipt,dimensions).info();
            });
        } catch (RuntimeException failure) {
            if (intent!=null) {
                try { delete(intent.id(),owner); }
                catch (ImageException missing) { if (missing.status!=404) { failure.addSuppressed(missing); throw compensation(failure); } }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); throw compensation(failure); }
            }
            throw failure;
        } finally { uploads.release(); }
    }
    private ImageException compensation(RuntimeException failure) {
        var error=new ImageException(503,"UPLOAD_CLEANUP_PENDING","上传未完成，原件清理也未完成；已保留记录，请在图片列表重试清理，不要清空数据目录");
        error.addSuppressed(failure); return error;
    }
    public void delete(long id,long owner) {
        transactions.executeWithoutResult(tx -> {
            ImageRecord first=record(id,owner,false); lockedBase(first.baseId(),owner);
            record(id,owner,false); images.markDeleting(id);
        });
        cleanup(id,owner);
    }
    private void cleanup(long id,long owner) {
        try {
            transactions.executeWithoutResult(tx -> {
                ImageRecord first=record(id,owner,false); lockedBase(first.baseId(),owner);
                ImageRecord current=record(id,owner,false);
                if (current.info().lifecycleStatus().equals("ACTIVE")) throw new ImageException(409,"DOCUMENT_CLEANUP_CONFLICT","图片状态已变化，请刷新后重试删除");
                try {
                    if (current.info().sha256()==null) files.discardReservation(Long.toString(current.baseId()),current.reservation());
                    else files.delete(Long.toString(current.baseId()),current.receipt());
                } catch (StorageException error) {
                    // The committed deleting intent permits idempotent retry after a
                    // previous file deletion followed by DB/connection failure.
                    if (error.code()!=StorageException.Code.NOT_FOUND) throw error;
                }
                images.remove(id);
            });
        } catch (RuntimeException failure) {
            try {
                transactions.executeWithoutResult(tx -> {
                    ImageRecord current=record(id,owner,false); lockedBase(current.baseId(),owner);
                    images.cleanupFailed(id,"原件清理未完成，请关闭占用程序、恢复权限或检查存储后重试删除");
                });
            } catch (RuntimeException recording) { failure.addSuppressed(recording); }
            throw failure;
        }
    }
    @Override public void markForDeletion(long base,long owner) { images.markAllDeleting(base,owner); }
    @Override public void clean(long base,long owner) {
        for (ImageRecord document : images.list(base,owner)) {
            try { cleanup(document.id(),owner); }
            catch (ImageException missing) { if (missing.status!=404) throw missing; }
        }
    }
    public void content(long id,long owner,com.medicalrag.storage.OriginalInspection<Void> output) {
        // Keep the KB lock while reading: deletion/upload on this library cannot race
        // the stream; other libraries remain independent.
        transactions.executeWithoutResult(tx -> {
            ImageRecord first=record(id,owner,true); requireAvailable(lockedBase(first.baseId(),owner));
            ImageRecord current=record(id,owner,true);
            files.inspect(Long.toString(current.baseId()),current.receipt(),output);
        });
    }
}
