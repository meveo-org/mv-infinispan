/**
 * 
 */
package org.meveo.modules.infinispan;

import java.util.Map;

import org.meveo.admin.exception.BusinessException;
import org.meveo.model.persistence.DBStorageType;
import org.meveo.persistence.DBStorageTypeService;
import org.meveo.service.admin.impl.ModuleInstallationContext;
import org.meveo.service.script.ScriptInstanceService;
import org.meveo.service.script.module.ModuleScript;

public class InfinispanInstallationScript extends ModuleScript {

    DBStorageTypeService dbStorageTypeService = getCDIBean(DBStorageTypeService.class);
    ScriptInstanceService scriptInstanceService = getCDIBean(ScriptInstanceService.class);
    ModuleInstallationContext installationContext = getCDIBean(ModuleInstallationContext.class);
  
  	public void execute(Map<String, Object> methodContext) throws BusinessException {
      postInstallModule(methodContext);
    }
    
    @Override
    public void postInstallModule(Map<String, Object> methodContext) throws BusinessException {
        // Register new storage type
        DBStorageType infinispanStorage = new DBStorageType();
        infinispanStorage.setCode("INFINISPAN");
        infinispanStorage.setStorageImplScript(scriptInstanceService.findByCode("org.meveo.modules.infinispan.InfinispanStorage"));
        dbStorageTypeService.create(infinispanStorage);
    }
  
    @Override
    public void preUninstallModule(Map<String, Object> methodContext) throws BusinessException {
      DBStorageType infinispanStorageType = dbStorageTypeService.find("INFINISPAN");
      if (infinispanStorageType != null) {
      	dbStorageTypeService.delete(infinispanStorageType);
      }
    }
    
}
