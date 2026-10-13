package com.medicalrag.storage;

import java.io.InputStream;
import java.io.OutputStream;
import com.medicalrag.auth.AdministratorService;
import com.medicalrag.knowledgebase.KnowledgeBaseService;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** No HTTP routes. A6/A7 must resolve a resource ID and its DB receipt before reading/deleting. */
@Service
public class KnowledgeBaseFileStorage {
    private final LocalOriginalStore store;
    private final AdministratorService administrators;
    private final KnowledgeBaseService knowledgeBases;
    KnowledgeBaseFileStorage(LocalOriginalStore store, AdministratorService administrators, KnowledgeBaseService knowledgeBases) {
        this.store = store; this.administrators = administrators; this.knowledgeBases = knowledgeBases;
    }
    private StorageScope scope(String knowledgeBaseId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) throw new AuthenticationCredentialsNotFoundException("请先登录本地管理员账号");
        if (authentication.getAuthorities().stream().noneMatch(role -> role.getAuthority().equals("ROLE_ADMIN"))) throw new AccessDeniedException("当前账号没有管理员权限");
        long id = KnowledgeBaseService.parseId(knowledgeBaseId);
        long owner = administrators.current(authentication.getName()).id();
        knowledgeBases.detail(id, owner);
        return new StorageScope(id);
    }
    /** Caller owns the source stream and must close it (e.g. try-with-resources). */
    public StoredOriginal save(String knowledgeBaseId, String originalName, OriginalType expectedType, InputStream source) {
        return store.save(scope(knowledgeBaseId), originalName, expectedType, source);
    }
    public StorageReservation reserve(String knowledgeBaseId, OriginalType type) {
        return store.reserve(scope(knowledgeBaseId), type);
    }
    public StoredOriginal saveReserved(String knowledgeBaseId, StorageReservation reservation, String name, InputStream source) {
        return store.saveReserved(scope(knowledgeBaseId), reservation, name, source);
    }
    /** Only use the server reservation from an incomplete DB record, never request fields. */
    public void discardReservation(String knowledgeBaseId, StorageReservation reservation) {
        store.discardReservation(scope(knowledgeBaseId), reservation);
    }
    public <T> T inspect(String knowledgeBaseId, StoredOriginal receipt, OriginalInspection<T> inspection) {
        return store.inspect(scope(knowledgeBaseId), receipt, inspection);
    }
    /** Caller owns destination; all store-owned file handles are closed before returning. */
    public void copyTo(String knowledgeBaseId, StoredOriginal receipt, OutputStream destination) {
        store.copyTo(scope(knowledgeBaseId), receipt, destination);
    }
    /** Future business code must invalidate the resource before cleanup and record/retry failures. */
    public void delete(String knowledgeBaseId, StoredOriginal receipt) { store.delete(scope(knowledgeBaseId), receipt); }
}
