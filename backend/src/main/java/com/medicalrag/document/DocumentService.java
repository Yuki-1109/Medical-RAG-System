package com.medicalrag.document;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Semaphore;
import com.medicalrag.knowledgebase.*;
import com.medicalrag.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@org.springframework.core.annotation.Order(0)
@Service
public class DocumentService implements KnowledgeBaseMaterialCleaner {
    private final DocumentRepository documents;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseMaterials materials;
    private final KnowledgeBaseFileStorage files;
    private final PdfValidator pdfs;
    private final TransactionTemplate transactions;
    private final Semaphore uploads=new Semaphore(2);
    public DocumentService(DocumentRepository documents, KnowledgeBaseRepository bases, KnowledgeBaseMaterials materials,
            KnowledgeBaseFileStorage files, PdfValidator pdfs, PlatformTransactionManager manager) {
        this.documents=documents; this.bases=bases; this.materials=materials; this.files=files; this.pdfs=pdfs;
        transactions=new TransactionTemplate(manager);
    }
    static long parseId(String value) {
        try { if (value==null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new DocumentException(400,"INVALID_DOCUMENT_ID","文献 ID 格式无效，请从文献列表选择"); }
    }
    static String filename(String value) {
        if (value==null || value.isBlank() || value.length()>255 || value.contains("/") || value.contains("\\") || value.contains(":")
                || value.codePoints().anyMatch(Character::isISOControl) || !value.strip().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                || value.strip().length()<=4) throw new DocumentException(400,"INVALID_PDF_FILENAME","请选择名称有效的 PDF 文件，文件名不能包含路径或控制字符");
        return value.strip();
    }
    private KnowledgeBase lockedBase(long id,long owner) {
        materials.requireSupportedStage();
        return bases.find(id,owner,true).orElseThrow(KnowledgeBaseException::missing);
    }
    private void requireAvailable(KnowledgeBase base) {
        if (base.deletionPending()) throw new DocumentException(409,"KNOWLEDGE_BASE_DELETING","知识库删除未完成，请重试删除，不能上传或读取资料");
    }
    public List<DocumentInfo> list(long base,long owner) {
        materials.requireSupportedStage(); bases.find(base,owner,false).orElseThrow(KnowledgeBaseException::missing);
        return documents.list(base,owner).stream().map(DocumentRecord::info).toList();
    }
    DocumentRecord record(long id,long owner,boolean active) {
        DocumentRecord record=documents.find(id,owner).orElseThrow(DocumentException::missing);
        KnowledgeBase base=bases.find(record.baseId(),owner,false).orElseThrow(DocumentException::missing);
        if (active && (base.deletionPending() || !record.info().lifecycleStatus().equals("ACTIVE"))) throw DocumentException.missing();
        return record;
    }
    public DocumentInfo detail(long id,long owner) { return record(id,owner,true).info(); }
    public DocumentInfo upload(long base,long owner,String name,InputStream source) {
        name=filename(name);
        if (!uploads.tryAcquire()) throw new DocumentException(429,"UPLOAD_BUSY","当前已有上传任务，请等待完成后重试");
        DocumentRecord intent=null;
        try {
            String normalized=name;
            // Persist the server-generated path BEFORE file I/O, so even a crash or DB
            // activation failure has a retryable cleanup record. This is not an active PDF.
            intent=transactions.execute(tx -> {
                requireAvailable(lockedBase(base,owner));
                return documents.reserve(base,normalized,files.reserve(Long.toString(base),OriginalType.PDF));
            });
            DocumentRecord reserved=intent;
            return transactions.execute(tx -> {
                requireAvailable(lockedBase(base,owner));
                DocumentRecord current=record(reserved.id(),owner,false);
                if (!current.info().lifecycleStatus().equals("UPLOADING")) throw DocumentException.missing();
                StoredOriginal receipt=files.saveReserved(Long.toString(base),current.reservation(),normalized,source);
                int pages=files.inspect(Long.toString(base),receipt,pdfs::pageCount);
                return documents.activate(current.id(),receipt,pages).info();
            });
        } catch (RuntimeException failure) {
            if (intent!=null) {
                try { delete(intent.id(),owner); }
                catch (DocumentException missing) { if (missing.status!=404) { failure.addSuppressed(missing); throw compensation(failure); } }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); throw compensation(failure); }
            }
            throw failure;
        } finally { uploads.release(); }
    }
    private DocumentException compensation(RuntimeException failure) {
        var error=new DocumentException(503,"UPLOAD_CLEANUP_PENDING","上传未完成，原件清理也未完成；已保留记录，请在文献列表重试清理，不要清空数据目录");
        error.addSuppressed(failure); return error;
    }
    public void delete(long id,long owner) {
        transactions.executeWithoutResult(tx -> {
            DocumentRecord first=record(id,owner,false); lockedBase(first.baseId(),owner);
            record(id,owner,false); documents.markDeleting(id);
        });
        cleanup(id,owner);
    }
    private void cleanup(long id,long owner) {
        try {
            transactions.executeWithoutResult(tx -> {
                DocumentRecord first=record(id,owner,false); lockedBase(first.baseId(),owner);
                DocumentRecord current=record(id,owner,false);
                if (current.info().lifecycleStatus().equals("ACTIVE")) throw new DocumentException(409,"DOCUMENT_CLEANUP_CONFLICT","文献状态已变化，请刷新后重试删除");
                try {
                    if (current.info().sha256()==null) files.discardReservation(Long.toString(current.baseId()),current.reservation());
                    else files.delete(Long.toString(current.baseId()),current.receipt());
                } catch (StorageException error) {
                    // The committed deleting intent permits idempotent retry after a
                    // previous file deletion followed by DB/connection failure.
                    if (error.code()!=StorageException.Code.NOT_FOUND) throw error;
                }
                documents.remove(id);
            });
        } catch (RuntimeException failure) {
            try {
                transactions.executeWithoutResult(tx -> {
                    DocumentRecord current=record(id,owner,false); lockedBase(current.baseId(),owner);
                    documents.cleanupFailed(id,"原件清理未完成，请关闭占用程序、恢复权限或检查存储后重试删除");
                });
            } catch (RuntimeException recording) { failure.addSuppressed(recording); }
            throw failure;
        }
    }
    @Override public void markForDeletion(long base,long owner) { documents.markAllDeleting(base,owner); }
    @Override public void clean(long base,long owner) {
        for (DocumentRecord document : documents.list(base,owner)) {
            try { cleanup(document.id(),owner); }
            catch (DocumentException missing) { if (missing.status!=404) throw missing; }
        }
    }
    public void content(long id,long owner,com.medicalrag.storage.OriginalInspection<Void> output) {
        // Keep the KB lock while reading: deletion/upload on this library cannot race
        // the stream; other libraries remain independent.
        transactions.executeWithoutResult(tx -> {
            DocumentRecord first=record(id,owner,true); requireAvailable(lockedBase(first.baseId(),owner));
            DocumentRecord current=record(id,owner,true);
            files.inspect(Long.toString(current.baseId()),current.receipt(),output);
        });
    }
}
