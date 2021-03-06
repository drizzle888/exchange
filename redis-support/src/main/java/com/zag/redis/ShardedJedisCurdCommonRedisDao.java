package com.zag.redis;

import com.zag.redis.annotation.*;
import com.zag.redis.util.ExpressionUtil;
import com.zag.redis.util.RedisUtil;
import com.zag.redis.util.SortedSetAssist;
import com.zag.redis.bean.BaseRedisObject;
import com.zag.redis.spi.ShardedJedisRedis;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import redis.clients.jedis.PipelineBase;
import redis.clients.jedis.ShardedJedis;
import redis.clients.jedis.ShardedJedisPipeline;
import redis.clients.util.Pool;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.*;

import static com.google.common.collect.Lists.newArrayList;

public class ShardedJedisCurdCommonRedisDao<T extends BaseRedisObject<ID>, ID extends Serializable> extends ShardedJedisRedis {

    private static final String SEPARATOR = ":";

    protected Logger logger = LoggerFactory.getLogger(getClass());

    private Class<T> entityClass;

    /**
     * ro key
     */
    private String keyPrefix = null;
    private boolean isExistRo = false;

    private String roLockKeyPrefix = null;
    private boolean isExistRoLock = false;
    private RoLock roLock = null;

    private String roSortedSetKey = null;
    private boolean isExistRoSortedSet = false;
    private RoSortedSet roSortedSet = null;

    Expression expression = null;

    Map<String, FieldSortedSet> fieldName_Annotation_Map = null;
    Map<FieldSortedSet, SortedSetAssist<T, ID>> fieldInSortedSetMap = null;

    Map<String, MethodSortedSet> methodName_Annotation_Map = null;
    Map<MethodSortedSet, SortedSetAssist<T, ID>> methodInSortedSetMap = null;

    public String getKeyByParams(Object... params) {
        StringBuffer key = new StringBuffer(getKeyPrefix());
        if (params != null && params.length > 0) {
            for (Object param : params) {
                key.append(SEPARATOR).append(String.valueOf(param));
            }
        }
        return key.toString();
    }

    /**
     * @param fieldName ???????????????
     * @param fieldValue ??????????????????
     * @author stone(by lei)
     * @date 2017???8???15???
     */
    public String getFieldSortedSetKey(String fieldName, Object fieldValue) {
        StringBuffer key = new StringBuffer();
        FieldSortedSet fieldSortedSet = fieldName_Annotation_Map.get(fieldName);
        if (fieldSortedSet == null) {
            throw new RuntimeException("[" + fieldName + "]--> FieldSortedSet is null");
        }
        String value = (fieldValue != null ? fieldValue.toString() : "");
        if ("".equals(fieldSortedSet.prefix())) {
            key.append(getKeyPrefix()).append(SEPARATOR).append(fieldName).append(SEPARATOR).append(value);
        } else {
            key.append(fieldSortedSet.prefix()).append(SEPARATOR).append(fieldName).append(SEPARATOR).append(value);
        }
        return key.toString();
    }

    /**
     * @param methodName ??????????????????
     * @param fieldValue ????????????????????????
     * @author stone(by lei)
     * @date 2017???8???15???
     */
    public String getMethodSortedSetKey(String methodName, Object fieldValue) {
        StringBuffer key = new StringBuffer();
        MethodSortedSet methodSortedSet = methodName_Annotation_Map.get(methodName);
        if (methodSortedSet == null)
            throw new RuntimeException("[" + methodName + "]--> MethodSortedSet is null");
        String value = (fieldValue != null ? fieldValue.toString() : "");
        if (methodSortedSet.prefix().equals("")) {
            key.append(getKeyPrefix()).append(SEPARATOR).append(methodName).append(SEPARATOR).append(value);
        } else {
            key.append(methodSortedSet.prefix()).append(SEPARATOR).append(methodName).append(SEPARATOR).append(value);
        }
        return key.toString();
    }

    public String getKeyPrefix() {
        return keyPrefix.toString();
    }

    public String getRoSortedSetKey() {
        return roSortedSetKey;
    }

    //ro
    public String getHashKey(Serializable id) {
        return new StringBuffer(getKeyPrefix()).append(SEPARATOR).append(id).toString();
    }

    //ro
    public String getHashKeyFromIdByte(byte[] byteId) {
        return new StringBuffer(getKeyPrefix()).append(SEPARATOR).append(new String(byteId)).toString();
    }

    //lock
    public String getRoLockKeyPrefix() {
        return roLockKeyPrefix.toString();
    }

    //lock
    public String getLockHashKey(Serializable id) {
        return new StringBuffer(getRoLockKeyPrefix()).append(SEPARATOR).append(id).toString();
    }

    //lock
    public String getLockHashKeyFromIdByte(byte[] byteId) {
        return new StringBuffer(getRoLockKeyPrefix()).append(SEPARATOR).append(new String(byteId)).toString();
    }

    public String getRoLockKeyByParams(Object... params) {
        StringBuffer key = new StringBuffer(getRoLockKeyPrefix());
        if (params != null && params.length > 0) {
            for (Object param : params) {
                key.append(SEPARATOR).append(param.toString());
            }
        }
        return key.toString();
    }

    @SuppressWarnings("unchecked")
    public ShardedJedisCurdCommonRedisDao() {
        super();

        /** getClass().getGenericSuperclass()??????????????? Class ??????????????????????????????????????????????????? void???
         *  ?????????????????? Type(Class<T>??????????????????)?????????????????????ParameterizedType?????? 
         *  getActualTypeArguments()?????????????????????????????????????????? Type ?????????????????? 
         *  [0]??????????????????????????????????????? 
         *  ??????????????????????????????????????????????????????????????????*/
        entityClass = (Class<T>) ((ParameterizedType) this.getClass().getGenericSuperclass()).getActualTypeArguments()[0];

        //???ro????????????
        Ro ro = entityClass.getAnnotation(Ro.class);
        isExistRo = (ro == null ? false : true);
        if (isExistRo) {
            keyPrefix = ro.key().intern();
        } else {
            //keyPrefix.append(entityClass.getCanonicalName());
            throw new RuntimeException("not find Ro annotation");
        }

        //???roLock?????????
        roLock = entityClass.getAnnotation(RoLock.class);
        isExistRoLock = (roLock == null ? false : true);
        if (isExistRoLock) {
            roLockKeyPrefix = roLock.key().intern();
        }

        ExpressionParser parser = new SpelExpressionParser();

        //???roSortedSet?????? ?????????ro??????
        roSortedSet = entityClass.getAnnotation(RoSortedSet.class);
        isExistRoSortedSet = (roSortedSet == null ? false : true);
        if (isExistRoSortedSet) {
            roSortedSetKey = (getKeyPrefix() + SEPARATOR + roSortedSet.key()).intern();
            if (StringUtils.isNotBlank(roSortedSet.score())) {
                expression = parser.parseExpression(roSortedSet.score());
            }
        }

        if (entityClass != null) {
            /**????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? 
             * entity.getFields();????????????????????????????????????????????????????????????????????? 
             * ???class???getDeclared**()??????????????????????????????????????????????????????????????? 
             * ??????API 
             * */
            Field[] fields = entityClass.getDeclaredFields();
            if (fields != null && fields.length > 0) {
                for (Field field : fields) {
                    if (!Modifier.isFinal(field.getModifiers())) {
                        field.setAccessible(true);//??????????????????
                        //??????????????????????????????
                        FieldSortedSet fieldSortedSet = field.getAnnotation(FieldSortedSet.class);
                        boolean isExistFieldSortedSet = (fieldSortedSet == null ? false : true);
                        if (isExistFieldSortedSet) {
                            if (fieldInSortedSetMap == null) {
                                fieldInSortedSetMap = new HashMap<>();
                            }
                            if (fieldName_Annotation_Map == null) {
                                fieldName_Annotation_Map = new HashMap<>();
                            }
                            fieldInSortedSetMap.put(
                                    fieldSortedSet,
                                    new SortedSetAssist<T, ID>(
                                            field.getName(),
                                            StringUtils.isBlank(fieldSortedSet.prefix()) ? getKeyPrefix() + SEPARATOR + field.getName() : fieldSortedSet.prefix() + SEPARATOR + field.getName(),
                                            parser.parseExpression(fieldSortedSet.key()),
                                            StringUtils.isNotBlank(fieldSortedSet.score()) ? parser.parseExpression(fieldSortedSet.score()) : null
                                    )
                            );
                            fieldName_Annotation_Map.put(field.getName(), fieldSortedSet);
                        }
                    }
                }
            }

            Method[] methods = entityClass.getMethods();
            if (methods != null && methods.length > 0) {
                for (Method method : methods) {
                    if (!Modifier.isFinal(method.getModifiers())) {
                        method.setAccessible(true);//??????????????????
                        //??????????????????????????????
                        MethodSortedSet methodSortedSet = method.getAnnotation(MethodSortedSet.class);
                        boolean isExistMethodSortedSet = (methodSortedSet == null ? false : true);
                        if (isExistMethodSortedSet) {
                            if (methodInSortedSetMap == null) {
                                methodInSortedSetMap = new HashMap<>();
                            }
                            if (methodName_Annotation_Map == null) {
                                methodName_Annotation_Map = new HashMap<>();
                            }
                            methodInSortedSetMap.put(
                                    methodSortedSet,
                                    new SortedSetAssist<T, ID>(
                                            method.getName(),
                                            StringUtils.isBlank(methodSortedSet.prefix()) ? getKeyPrefix() + SEPARATOR + method.getName() : methodSortedSet.prefix() + SEPARATOR + method.getName(),
                                            parser.parseExpression(methodSortedSet.key()),
                                            StringUtils.isNotBlank(methodSortedSet.score()) ? parser.parseExpression(methodSortedSet.score()) : null
                                    )
                            );
                            methodName_Annotation_Map.put(method.getName(), methodSortedSet);
                        }
                    }
                }
            }
        }
    }

    /**
     * ?????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    private T instance() {
        try {
            return entityClass.newInstance();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * ????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public T findOne(ID id) {
        if (id == null) {
            return null;
        }
        Map<byte[], byte[]> map = hgetAll(getHashKey(id));
        if (MapUtils.isNotEmpty(map)) {
            T ro = instance();
            ro.fromMap(map);
            return ro;
        } else
            return null;
    }

    /**
     * ??????roSortedSetKey?????????????????????key??????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public List<String> getKeyListFromSortedSet(String roSortedSetKey) {
        Set<byte[]> ids = zRange(roSortedSetKey, 0, -1);
        if (CollectionUtils.isNotEmpty(ids)) {
            List<String> keys = new ArrayList<>(ids.size());
            for (byte[] bid : ids) {
                keys.add(getHashKeyFromIdByte(bid));
            }
            return keys;
        }
        return newArrayList();
    }

    /**
     * ??????roSortedSetKey ids?????????????????????key??????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public List<String> getKeyListByIdSet(Set<byte[]> ids) {
        if (CollectionUtils.isNotEmpty(ids)) {
            List<String> keys = new ArrayList<>(ids.size());
            for (byte[] bid : ids) {
                keys.add(getHashKeyFromIdByte(bid));
            }
            return keys;
        }
        return newArrayList();
    }

    /**
     * ??????????????????????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public List<T> findAll() {
        List<String> keys = getKeyListFromSortedSet(this.getRoSortedSetKey());
        return findByKeys(keys);
    }

    /**
     * ????????????????????????????????????????????????
     *
     * @param page ????????????: 1
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public List<T> findByScoreAsc(Integer page) {
        Set<byte[]> ids = zRange(this.getRoSortedSetKey(), Math.max(0, page - 1) * 10, Math.max(1, page) * 10 - 1);
        return findByKeys(toHashKeys(ids));
    }

    /**
     * ????????????????????????????????????????????????
     *
     * @param page ????????????: 1
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public List<T> findByScoreDesc(Integer page) {
        Set<byte[]> ids = zrevrange(this.getRoSortedSetKey(), Math.max(0, page - 1) * 10, Math.max(1, page) * 10 - 1);
        return findByKeys(toHashKeys(ids));
    }
    /**
     * ????????????????????????????????????????????????
     *
     * @param page ????????????: 1
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public List<T> findByScoreDesc(Integer page, Integer size) {
    	if(size == null || size < 1){
    		size = 10;
    	}
    	Set<byte[]> ids = zrevrange(this.getRoSortedSetKey(), Math.max(0, page - 1) * size, Math.max(1, page) * size - 1);
    	return findByKeys(toHashKeys(ids));
    }

    /**
     * ????????????keys
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    private List<String> toHashKeys(Collection<byte[]> ids) {
        if (CollectionUtils.isNotEmpty(ids)) {
            List<String> keys = new ArrayList<>(ids.size());
            for (byte[] bid : ids) {
                keys.add(getHashKeyFromIdByte(bid));
            }
            return keys;
        } else {
            return newArrayList();
        }
    }

    /**
     * ??????????????????id??????????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public List<T> findByIds(Iterable<ID> ids) {
        if (ids == null || !ids.iterator().hasNext())
            return newArrayList();
        List<String> keys = new ArrayList<String>();
        for (ID id : ids) {
            keys.add(getHashKey(id));
        }
        return findByKeys(keys);
    }
    
    /**
     * ??????????????????id??????????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public List<T> findByIdsWithNull(Iterable<ID> ids) {
        if (ids == null || !ids.iterator().hasNext())
            return newArrayList();
        List<String> keys = new ArrayList<String>();
        for (ID id : ids) {
            keys.add(getHashKey(id));
        }
        return findByKeysWithNull(keys);
    }


    /**
     * ??????????????????id??????????????????
     *
     * @author shutao.gong
     * @date 2017???8???13???
     */
    public List<T> findByStrIds(Iterable<String> ids) {
        if (ids == null || !ids.iterator().hasNext())
            return newArrayList();
        List<String> keys = new ArrayList<String>();
        for (String id : ids) {
            keys.add(getHashKey(id));
        }
        return findByKeys(keys);
    }

    /**
     * ??????id??????????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public List<T> findByIds(Set<byte[]> ids) {
        if (CollectionUtils.isEmpty(ids))
            return newArrayList();
        List<String> keys = new ArrayList<String>(ids.size());
        for (byte[] id : ids) {
            keys.add(getHashKeyFromIdByte(id));
        }
        return findByKeys(keys);
    }

    /**
     * ??????id??????????????????map key id  value t??????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public Map<ID, T> findMapByIds(Iterable<ID> ids) {
        if (ids == null || !ids.iterator().hasNext())
            return new HashMap<ID, T>();
        List<String> keys = new ArrayList<String>();
        for (ID id : ids) {
            keys.add(getHashKey(id));
        }
        List<T> list = findByKeys(keys);
        if (!CollectionUtils.isEmpty(list)) {
            Map<ID, T> result = new HashMap<ID, T>();
            for (T t : list) {
                if (t != null) {
                    result.put(t.getId(), t);
                }
            }
            return result;
        }
        return new HashMap<ID, T>();
    }

    /**
     * ??????id??????map
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public Map<ID, T> findMapByIds(Set<byte[]> ids) {
        if (CollectionUtils.isEmpty(ids))
            return new HashMap<ID, T>();
        List<String> keys = new ArrayList<String>(ids.size());
        for (byte[] id : ids) {
            keys.add(getHashKeyFromIdByte(id));
        }
        List<T> list = findByKeys(keys);
        if (!CollectionUtils.isEmpty(list)) {
            Map<ID, T> result = new HashMap<ID, T>();
            for (T t : list) {
                if (t != null) {
                    result.put(t.getId(), t);
                }
            }
            return result;
        }
        return new HashMap<ID, T>();
    }

    /**
     * ??????keys??????????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    @SuppressWarnings("unchecked")
    public List<T> findByKeys(List<String> keys) {
        List<T> result = newArrayList();
        if (CollectionUtils.isNotEmpty(keys)) {
            List<Object> list = pipeHgetall(keys);
            for (int i = 0; i < list.size(); i++) {
                Map<byte[], byte[]> map = (Map<byte[], byte[]>) list.get(i);
                if (map != null && !map.isEmpty()) {
                    T ro = instance();
                    try {
                        ro.fromMap(map);
                    } catch (Exception e) {
                        logger.error("ro.fromMap", e);
                    }
                    result.add(ro);
                }
            }
        }
        return result;
    }
    
    @SuppressWarnings("unchecked")
    public List<T> findByKeysWithNull(List<String> keys) {
        List<T> result = newArrayList();
        if (CollectionUtils.isNotEmpty(keys)) {
            List<Object> list = pipeHgetall(keys);
            for (int i = 0; i < list.size(); i++) {
                Map<byte[], byte[]> map = (Map<byte[], byte[]>) list.get(i);
                if (map == null || map.isEmpty()) {
                    // ?????????????????????, ???????????????!!!!!
                    result.add(null);
                } else {
                    T ro = instance();
                    try {
                        ro.fromMap(map);
                    } catch (Exception e) {
                        logger.error("ro.fromMap", e);
                    }
                    result.add(ro);
                }
            }
        }
        return result;
    }

    /**
     * @author stone(by lei)
     * @date 2017???9???13???
     */
    public T findByKey(String key) {
        Map<byte[], byte[]> map = (Map<byte[], byte[]>) hgetAll(key);
        if (map == null || map.isEmpty()) {
            return null;
        } else {
            T ro = instance();
            try {
                ro.fromMap(map);
            } catch (Exception e) {
                logger.error("ro.fromMap", e);
            }
            return ro;
        }
    }

    /**
     * ????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public void save(T ro) {
        if (isExistRoSortedSet) {
            long score = ExpressionUtil.getScore(new StandardEvaluationContext(ro), expression);
            zadd(this.getRoSortedSetKey(), score, RedisUtil.toByteArray(ro.getId()));
        }

        if (MapUtils.isNotEmpty(fieldInSortedSetMap)) {
            for (FieldSortedSet fieldSortedSet : fieldInSortedSetMap.keySet()) {
                SortedSetAssist<T, ID> field = fieldInSortedSetMap.get(fieldSortedSet);
                zadd(field.getKey(ro), field.getScore(ro), RedisUtil.toByteArray(ro.getId()));
            }
        }

        if (MapUtils.isNotEmpty(methodInSortedSetMap)) {
            for (MethodSortedSet methodSortedSet : methodInSortedSetMap.keySet()) {
                SortedSetAssist<T, ID> method = methodInSortedSetMap.get(methodSortedSet);
                zadd(method.getKey(ro), method.getScore(ro), RedisUtil.toByteArray(ro.getId()));
            }
        }
        hmset(getHashKey(ro.getId()), ro.toMap());
    }


    /**
     * ????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    private void save(T ro, PipelineBase pipeline) {
        if (isExistRoSortedSet) {
            long score = ExpressionUtil.getScore(new StandardEvaluationContext(ro), expression);
            pipeline.zadd(RedisUtil.toByteArray(this.getRoSortedSetKey()), score, RedisUtil.toByteArray(ro.getId()));
        }

        if (MapUtils.isNotEmpty(fieldInSortedSetMap)) {
            for (FieldSortedSet fieldSortedSet : fieldInSortedSetMap.keySet()) {
                SortedSetAssist<T, ID> field = fieldInSortedSetMap.get(fieldSortedSet);
                pipeline.zadd(RedisUtil.toByteArray(field.getKey(ro)), field.getScore(ro), RedisUtil.toByteArray(ro.getId()));
            }
        }

        if (MapUtils.isNotEmpty(methodInSortedSetMap)) {
            for (MethodSortedSet methodSortedSet : methodInSortedSetMap.keySet()) {
                SortedSetAssist<T, ID> method = methodInSortedSetMap.get(methodSortedSet);
                pipeline.zadd(RedisUtil.toByteArray(method.getKey(ro)), method.getScore(ro), RedisUtil.toByteArray(ro.getId()));
            }
        }
        pipeline.hmset(RedisUtil.toByteArray(getHashKey(ro.getId())), ro.toMap());
    }

    /**
     * ????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    @SuppressWarnings("deprecation")
    public void save(Iterable<T> ros) {
        //??????????????????ComJedisRedis<ShardedJedis>
        Pool<ShardedJedis> pool = getPool();
        ShardedJedis jedis = null;
        try {
            jedis = pool.getResource();
            ShardedJedisPipeline jedisPipeline = jedis.pipelined();
            for (T ro : ros) {
                this.save(ro, jedisPipeline);
            }
            jedisPipeline.syncAndReturnAll();
        } catch (Exception e){
            logger.error("redis save iterator error.", e);
        }
        finally {
            pool.returnResource(jedis);
        }
    }

    /**
     * ??????id??????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public void delete(ID id) {
        delete(this.findOne(id));
    }

    /**
     * ??????????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public void delete(T ro) {
        if (ro != null) {
            if (isExistRoSortedSet) {
                zrem(this.getRoSortedSetKey(), RedisUtil.toByteArray(ro.getId()));
            }

            if (MapUtils.isNotEmpty(fieldInSortedSetMap)) {
                for (FieldSortedSet fieldSortedSet : fieldInSortedSetMap.keySet()) {
                    SortedSetAssist<T, ID> field = fieldInSortedSetMap.get(fieldSortedSet);
                    zrem(field.getKey(ro), RedisUtil.toByteArray(ro.getId()));
                }
            }

            if (MapUtils.isNotEmpty(methodInSortedSetMap)) {
                for (MethodSortedSet methodSortedSet : methodInSortedSetMap.keySet()) {
                    SortedSetAssist<T, ID> method = methodInSortedSetMap.get(methodSortedSet);
                    zrem(method.getKey(ro), RedisUtil.toByteArray(ro.getId()));
                }
            }
            del(getHashKey(ro.getId()));
        }
    }

    /**
     * ???????????????key
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public boolean isExists(ID id) {
        return exists(getHashKey(id));
    }

    /**
     * ?????????????????????????????????
     *
     * @author stone(by lei)
     * @date 2017???8???12???
     */
    public Long count() {
        if (isExistRoSortedSet) {
            return zCard(this.getRoSortedSetKey());
        }
        throw new UnsupportedOperationException();
    }

    public void deleteAll() {
        if (isExistRoSortedSet) {
            List<String> keys = getKeyListFromSortedSet(this.getRoSortedSetKey());
            if (CollectionUtils.isNotEmpty(keys)) {
                for (String key : keys) {
                    super.del(key);
                }
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    public void pipeDelete(List<String> keys) {
        Pool<ShardedJedis> pool = getPool();
        ShardedJedis jedis = null;
        try {
            jedis = pool.getResource();
            ShardedJedisPipeline jedisPipeline = jedis.pipelined();
            for (String key : keys) {
                jedisPipeline.del(key);
            }
            jedisPipeline.syncAndReturnAll();
        } catch (Exception e){
            logger.error("redis save iterator error.", e);
        }
        finally {
            if (Objects.nonNull(jedis)) {
                jedis.close();
            }
        }
    }
}
