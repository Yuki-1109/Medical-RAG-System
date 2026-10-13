package com.medicalrag.knowledgebase;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Committed tombstone first; keep ownership until every resource has been cleaned. */
@Service
public class KnowledgeBaseDeletion {
    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseMaterials materials;
    private final ObjectProvider<KnowledgeBaseMaterialCleaner> cleaners;
    private final TransactionTemplate transactions;
    public KnowledgeBaseDeletion(KnowledgeBaseRepository bases, KnowledgeBaseMaterials materials,
            ObjectProvider<KnowledgeBaseMaterialCleaner> cleaners, PlatformTransactionManager manager) {
        this.bases = bases; this.materials = materials; this.cleaners = cleaners;
        transactions = new TransactionTemplate(manager);
    }
    public void delete(long id, long owner) {
        delete(id,owner,null,null);
    }
    public void delete(long id,long owner,Long expectedDocuments,Long expectedPending) {
        transactions.executeWithoutResult(tx -> {
            KnowledgeBase current=bases.find(id, owner, true).orElseThrow(KnowledgeBaseException::missing);
            materials.requireSupportedStage();
            if ((expectedDocuments!=null && expectedDocuments!=current.documentCount())
                    || (expectedPending!=null && expectedPending!=current.pendingCleanupCount())) {
                throw new KnowledgeBaseException(409,"KNOWLEDGE_BASE_CHANGED","知识库资料数量已变化，本次未删除，请刷新并再次确认影响范围");
            }
            bases.markDeleting(id, owner);
            cleaners.orderedStream().forEach(cleaner -> cleaner.markForDeletion(id, owner));
        });
        // Upload also locks this KB row and checks deletion_pending before any I/O.
        // Failure keeps the committed tombstone and credentials for an explicit retry.
        cleaners.orderedStream().forEach(cleaner -> cleaner.clean(id, owner));
        transactions.executeWithoutResult(tx -> {
            bases.find(id, owner, true).orElseThrow(KnowledgeBaseException::missing);
            materials.requireNoRemainingMaterials(id);
            if (!bases.delete(id, owner)) throw KnowledgeBaseException.missing();
        });
    }
}
