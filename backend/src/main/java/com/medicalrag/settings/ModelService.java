package com.medicalrag.settings;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.medicalrag.settings.ModelTypes.*;

@Service
class ModelService {
    private final ModelRepository repository;
    private final ModelKeyStore keys;
    private final ModelAddressPolicy addresses;
    private final ModelConnectionTester tester;
    ModelService(ModelRepository repository, ModelKeyStore keys, ModelAddressPolicy addresses, ModelConnectionTester tester) {
        this.repository=repository; this.keys=keys; this.addresses=addresses; this.tester=tester;
    }
    List<Info> list(long owner) { return List.of(info(owner,Type.CHAT),info(owner,Type.EMBEDDING)); }
    private Info info(long owner, Type type) {
        return repository.find(owner,type).map(this::info).orElse(new Info(type,"OPENAI_COMPATIBLE","","",false,false,"UNTESTED",null,null,null,null,0));
    }
    private Info info(Stored config) {
        boolean key=config.cipher()!=null;
        return new Info(config.type(),config.protocol(),config.baseUrl(),config.modelId(),key,key,config.testStatus(),config.testCode(),
            ModelConnectionTester.message(config.testCode()),config.testedAt(),config.dimension(),config.revision());
    }
    @Transactional
    public Info save(long owner, Input input) {
        if (input==null) throw ModelException.invalid();
        Type type=ModelTypes.type(input.type());
        if (!"OPENAI_COMPATIBLE".equals(input.protocol()) || input.modelId()==null
                || !input.modelId().matches("[A-Za-z0-9][A-Za-z0-9_./:@-]{0,199}")
                || input.apiKey()!=null && !input.apiKey().isEmpty() && !input.apiKey().matches("[\\x21-\\x7e]{1,4096}")
                || input.deleteKey() && input.apiKey()!=null && !input.apiKey().isEmpty()) throw ModelException.invalid();
        addresses.base(input.baseUrl());
        repository.lockOwner(owner);
        String cipher=repository.find(owner,type).map(Stored::cipher).orElse(null);
        if (input.deleteKey()) cipher=null;
        else if (input.apiKey()!=null && !input.apiKey().isEmpty()) cipher=keys.encrypt(input.apiKey(),owner,type);
        // Keeping the old key doesn't require decryption; file management and configuration reads remain available.
        repository.save(owner,type,input,cipher);
        return info(owner,type);
    }
    Info test(long owner, TestInput input) {
        if (input==null) throw ModelException.invalid();
        Type type=ModelTypes.type(input.type());
        Stored config=repository.find(owner,type).orElseThrow(() -> new ModelException(409,"MODEL_NOT_CONFIGURED","请先保存当前类型的模型配置和 API Key"));
        if (config.cipher()==null) throw new ModelException(409,"MODEL_NOT_CONFIGURED","请先保存当前类型的 API Key");
        Integer dimension;
        try { dimension=tester.test(config,keys.decrypt(config.cipher(),owner,type)); }
        catch (ModelException error) {
            if (!repository.tested(config,error.code,null)) throw changed();
            throw error;
        }
        if (!repository.tested(config,null,dimension)) throw changed();
        return info(owner,type);
    }
    private ModelException changed() { return new ModelException(409,"MODEL_CONFIG_CHANGED","测试期间配置已更新，旧测试结果已丢弃，请测试当前配置"); }
}
