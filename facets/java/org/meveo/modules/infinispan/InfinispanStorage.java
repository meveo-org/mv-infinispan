/**
 * 
 */
package org.meveo.modules.infinispan;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.ArrayList;

import javax.naming.InitialContext;

import org.hibernate.search.annotations.Field;
import org.hibernate.search.annotations.Indexed;


import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.PersistenceConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.query.Search;
import org.infinispan.query.dsl.QueryFactory;

import org.meveo.service.script.CustomEntityClassLoader;
import org.meveo.admin.exception.BusinessException;
import org.meveo.admin.util.pagination.PaginationConfiguration;
import org.meveo.api.exception.EntityDoesNotExistsException;
import org.meveo.commons.utils.MeveoFileUtils;
import org.meveo.commons.utils.QueryBuilder;
import org.meveo.model.CustomEntity;
import org.meveo.model.crm.CustomFieldTemplate;
import org.meveo.model.customEntities.CustomEntityInstance;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.model.customEntities.CustomModelObject;
import org.meveo.model.customEntities.CustomRelationshipTemplate;
import org.meveo.model.persistence.CEIUtils;
import org.meveo.model.persistence.DBStorageType;
import org.meveo.model.persistence.JacksonUtil;
import org.meveo.model.storage.IStorageConfiguration;
import org.meveo.model.storage.Repository;
import org.meveo.persistence.PersistenceActionResult;
import org.meveo.persistence.StorageImpl;
import org.meveo.persistence.StorageQuery;
import org.meveo.service.base.QueryBuilderHelper;
import org.meveo.service.custom.CustomEntityTemplateCompiler;
import org.meveo.service.custom.CustomEntityTemplateService;
import org.meveo.service.script.Script;
import org.slf4j.Logger;
import org.infinispan.query.dsl.embedded.impl.EmbeddedQueryEngine;
import org.infinispan.AdvancedCache;

import com.github.javaparser.JavaParser;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.meveo.service.script.CharSequenceCompiler;
import org.meveo.service.crm.impl.CustomFieldTemplateService;


public class InfinispanStorage extends Script implements StorageImpl  {

	private static Logger log = org.slf4j.LoggerFactory.getLogger(InfinispanStorage.class);
	
	private static EmbeddedCacheManager cacheContainer;
    
	
	private CustomEntityTemplateCompiler cetCompiler = getCDIBean(CustomEntityTemplateCompiler.class);
	private CustomEntityTemplateService cetService = getCDIBean(CustomEntityTemplateService.class);;
    private static CustomEntityClassLoader ceiClassLoader;
    private CustomFieldTemplateService cftService = getCDIBean(CustomFieldTemplateService.class);
    private List<Class<?>> indexedEntities = new ArrayList<>();
	
	@Override
	public boolean exists(IStorageConfiguration storage, CustomEntityTemplate cet, String uuid) {
		return getCache(storage.getCode(), cet.getCode()).get(uuid) != null;
	}

	@Override
	public Map<String, Object> findById(IStorageConfiguration repository, CustomEntityTemplate cet, String uuid, Map<String, CustomFieldTemplate> cfts, Collection<String> fetchFields, boolean withEntityReferences) {
		var pojo = getCache(repository.getCode(), cet.getCode()).get(uuid);
		try {
			return JacksonUtil.toMap(pojo);
		} catch (IOException e) {
			log.error("Failed to convert pojo", e);
			return null;
		}
	}

	@Override
	public List<Map<String, Object>> find(StorageQuery query) throws EntityDoesNotExistsException {
        var cache = getCache(query.getStorageConfiguration().getCode(), query.getCet().getCode());
        if (cache.isEmpty()) {
            return new ArrayList<>();
        }

		query.getPaginationConfiguration();
		var cetClass = getClass(query.getCet());
		QueryBuilder queryBuilder = QueryBuilderHelper.getQuery(null, cetClass);
		var infinispanQuery = getQuery(queryBuilder, query.getStorageConfiguration().getCode(), query.getCet().getCode());
		return infinispanQuery.list()
				.stream()
				.map(t -> {
					try {
						return JacksonUtil.toMap(t);
					} catch (IOException e) {
						log.error("Failed to convert pojo", e);
						return null;
					}
				}).collect(Collectors.toList());
	}

	@Override
	public PersistenceActionResult createOrUpdate(Repository repository, IStorageConfiguration storageConf, CustomEntityInstance cei, Map<String, CustomFieldTemplate> customFieldTemplates, String foundUuid) throws BusinessException {
		var cetClass = getClass(cei.getCet());
		var pojo = CEIUtils.ceiToPojo(cei, cetClass);
		var cache = getCache(storageConf.getCode(), cei.getCetCode());
		
		if (foundUuid == null) {
			foundUuid = UUID.randomUUID().toString();
		}
		
		cache.put(foundUuid, pojo);
		
		return new PersistenceActionResult(foundUuid);
	}

	@Override
	public PersistenceActionResult addCRTByUuids(IStorageConfiguration repository, CustomRelationshipTemplate crt, Map<String, Object> relationValues, String sourceUuid, String targetUuid) throws BusinessException {
		return null;
	}

	@Override
	public void update(Repository repository, IStorageConfiguration conf, CustomEntityInstance cei) throws BusinessException {
		createOrUpdate(repository, conf, cei, cei.getFieldTemplates(), cei.getUuid());
	}

	@Override
	public void setBinaries(IStorageConfiguration repository, CustomEntityTemplate cet, CustomFieldTemplate cft, String uuid, List<File> binaries) throws BusinessException {

	}

	@Override
	public void remove(IStorageConfiguration storage, CustomEntityTemplate cet, String uuid) throws BusinessException {
		var cache = getCache(storage.getCode(), cet.getCode());
		cache.remove(uuid);
	}

	@Override
	public Integer count(IStorageConfiguration repository, CustomEntityTemplate cet, PaginationConfiguration paginationConfiguration) {
		var cetClass = getClass(cet);
		var cache = getCache(repository.getCode(), cet.getCode());
		// QueryBuilder queryBuilder = QueryBuilderHelper.getQuery(paginationConfiguration, cetClass);
		// var infinispanQuery = getQuery(queryBuilder, repository.getCode(), cet.getCode());
		var queryFactory = Search.getQueryFactory(cache);
        var q = queryFactory.from(cetClass)
            // .having("author.surname").eq("King")
            .build();

		return q.getResultSize();
	}
	
	public static DBStorageType getStorageType() {
		DBStorageType dbStorageType = new DBStorageType();
		dbStorageType.setCode("INFINISPAN");
		return dbStorageType;
	}
	
	/**
	 * Create the persisted cache
	 */
	@Override
	public void cetCreated(CustomEntityTemplate cet) {
        updateJavaFileWithAnnotations(cet);

        var cetClass = ceiClassLoader.compile(cet);

        indexedEntities.add(cetClass);
        initCacheContainer();

		for (var repo : cet.getRepositories()) {
			for (var storage : repo.getStorageConfigurations(getStorageType())) {
                String cacheName = storage.getCode() + "/" + cet.getCode();
                cacheContainer.removeCache(cacheName); //TODO remove
		        if (!cacheContainer.cacheExists(cacheName)) {
		        	PersistenceConfigurationBuilder confBuilder = new ConfigurationBuilder()
		        			.persistence()
		                    .passivation(false);
		            Configuration persistentFileConfig = confBuilder.addSingleFileStore()
		                    .location(cet.getCode())
		                    .preload(true)
		                    .purgeOnStartup(false)
                            .indexing()
                                .enable()
                                .indexLocalOnly(true)
                                .autoConfig(true)
                                .addIndexedEntity(cetClass)
		                    .build();
                    try {
                        var cache = cacheContainer.createCache(cacheName, persistentFileConfig);
                        log.info("Created cache {}", cacheName);
                    } catch (Exception e) {
                        log.info("Error when creating cache {}", cacheName, e);
                    }
		        } else {
                    log.info("Cache {} already exist", cacheName);
                }
			}
		}

	}

    private void initCacheContainer() {
        var globalConf = new GlobalConfigurationBuilder()
            .defaultCacheName("infinispan-storage")
            .classLoader(ceiClassLoader)
            .build();

        var confBuilder = new ConfigurationBuilder()
            .indexing()
            .enable()
            .indexLocalOnly(true)
            .autoConfig(true);

        for (var indexedEntity : indexedEntities) {
            log.info("Add index entity {}",indexedEntity.getName());
            confBuilder.addIndexedEntity(indexedEntity);
        }

        Configuration infinispanConfiguration = 
            // .withProperties(properties)
            confBuilder.build();

        cacheContainer = new DefaultCacheManager(globalConf, infinispanConfiguration);
    }

	@Override
	public void crtCreated(CustomRelationshipTemplate crt) throws BusinessException {
	}

	@Override
	public void cftCreated(CustomModelObject template, CustomFieldTemplate cft) {
		if (template instanceof CustomEntityTemplate) {
			updateJavaFileWithAnnotations((CustomEntityTemplate) template);
		}
	}

	@Override
	public void cetUpdated(CustomEntityTemplate oldCet, CustomEntityTemplate cet) {
        cetCreated(cet);
	}

	@Override
	public void crtUpdated(CustomRelationshipTemplate cet) throws BusinessException {

	}

	@Override
	public void cftUpdated(CustomModelObject template, CustomFieldTemplate oldCft, CustomFieldTemplate cft) {
		updateJavaFileWithAnnotations((CustomEntityTemplate) template);
	}

	@Override
	public void removeCft(CustomModelObject template, CustomFieldTemplate cft) {
		updateJavaFileWithAnnotations((CustomEntityTemplate) template);
	}

	@Override
	public void removeCet(CustomEntityTemplate cet) {
		
	}

	@Override
	public void removeCrt(CustomRelationshipTemplate crt) {

	}

	public InfinispanStorage () {
        if (this.ceiClassLoader == null) {
            this.ceiClassLoader = new CustomEntityClassLoader(cetService, this.getClass().getClassLoader());
        }

		if (cacheContainer == null) { 
	    	try {
                initCacheContainer();
                // initCacheContainer();
			// 	InitialContext initialContext = new InitialContext();
			// 	cacheContainer = (EmbeddedCacheManager) initialContext.lookup("java:jboss/infinispan/container/meveo");
            //     if (cacheContainer == null) {
            //         log.error("Cannot instantiate cache container (null)");
            //     }
			 } catch (Exception e) {
				log.error("Cannot instantiate cache container", e);
			}
		}
	}

	@Override
	public void init() {
	}

	@Override
	public <T> T beginTransaction(IStorageConfiguration repository, int stackedCalls) {
		return null;
	}

	@Override
	public void commitTransaction(IStorageConfiguration repository) {

	}

	@Override
	public void rollbackTransaction(int stackedCalls) {

	}

	@Override
	public void destroy() {
	}
	
	private Cache<String, CustomEntity> getCache(String code, String cetCode) {
		return cacheContainer.getCache(code + "/" + cetCode);
	}
	
	@SuppressWarnings("unchecked")
	public Class<? extends CustomEntity> getClass(CustomEntityTemplate cet) {
        try {
            return (Class<? extends CustomEntity>) ceiClassLoader.loadClass("org.meveo.model.customEntities." + cet.getCode());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
	}
	
    public org.infinispan.query.dsl.Query getQuery(QueryBuilder queryBuilder, String code, String cetCode) {
    	QueryFactory queryFactory = Search.getQueryFactory(getCache(code, cetCode));
    	queryBuilder.applyPagination(queryBuilder.getPaginationSortAlias());

    	org.infinispan.query.dsl.Query query = queryFactory.create(queryBuilder.toString());

    	if (queryBuilder.getPaginationConfiguration() != null) {
    		if (queryBuilder.getPaginationConfiguration().getFirstRow() != null) {
    			query.startOffset(queryBuilder.getPaginationConfiguration().getFirstRow());
    		}
    		if (queryBuilder.getPaginationConfiguration().getNumberOfRows() != null) {
    			query.maxResults(queryBuilder.getPaginationConfiguration().getNumberOfRows());
    		}
    	}
        
        for (Map.Entry<String, Object> e : queryBuilder.getParams().entrySet()) {
        	query.setParameter(e.getKey(), e.getValue());
        }
        return query;
    }
	
	private void updateJavaFileWithAnnotations(CustomEntityTemplate cet) {
        final File cetJavaDir = cetCompiler.getJavaCetDir(cet, cetService.findModuleOf(cet));
		final File javaFile = new File(cetJavaDir, cet.getCode() + ".java");
        var cfts = cftService.findByAppliesToNoCache(cet.getAppliesTo());

        log.info("Updating file {}", javaFile);
		try {
			var compilationUnit = JavaParser.parse(javaFile);
			var cetClass = compilationUnit.getClassByName(cet.getCode()).get();
            if (!cetClass.isAnnotationPresent(Indexed.class)) {
                cetClass.addAnnotation(Indexed.class);
            }
			cetClass.getFields()
				.forEach(field -> {
                    String fieldName = field.getVariables().get(0).getName().getIdentifier();
                    log.info("Adding Field annotation on {}", fieldName);
                    if (!field.isAnnotationPresent(Field.class) && cfts.containsKey(fieldName)) {
                        field.addAnnotation(Field.class);
                    }
                });
			MeveoFileUtils.writeAndPreserveCharset(compilationUnit.toString(), javaFile);
		} catch (Exception e) {
			log.error("Failed to update java file with infinispan annotation", e);
		}
	}

}
