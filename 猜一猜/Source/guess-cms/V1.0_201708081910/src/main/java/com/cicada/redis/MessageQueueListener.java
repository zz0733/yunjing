package com.cicada.redis;

import com.alibaba.fastjson.JSONObject;
import com.cicada.commons.Exception.ApiException;
import com.cicada.commons.utils.*;
import com.cicada.enums.ErrorCodeEnum;
import com.cicada.pojo.ClientEntity;
import com.cicada.pojo.Task;
import com.cicada.pojo.vo.LotteryResultVo;
import com.cicada.pojo.vo.TaskLotteryVo;
import com.cicada.service.IClientService;
import com.cicada.service.ITaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.LogManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.Serializable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * redis消息订阅
 * Created by Administrator on 2017/6/16.
 */
@Component("redisMessageQueueListener")
public class MessageQueueListener {

    private static final org.apache.log4j.Logger LOTTERY_LOGGER = LogManager.getLogger("lottery");
    @Autowired
    private ITaskService taskService;
    @Autowired
    private IClientService clientService;
    @Autowired
    private JacksonSerializer jacksonSerializer;

    /**
     * 监听Redis消息（开奖处理） 方法名必须为 handleMessage ，因为这是MessageDelegate的实现
     * @param lotteryMessage 消息内容
     * @param channel 消息频道
     * @throws ApiException
     */
    public void handleMessage(Serializable lotteryMessage,String channel) throws ApiException{
        final long taskId;
        try {

            if (channel.equals(ConstantInterface.REDIS_QUEUE_LOTTERY)) {
                LOTTERY_LOGGER.info(String.format("监听到下注开奖通知，频道：%s，参数：%s", channel, lotteryMessage));
                TaskLotteryVo taskLotteryVo = jacksonSerializer.fromJson(lotteryMessage.toString(), new TypeReference<TaskLotteryVo>() {
                });
                taskId = taskLotteryVo.getTaskId();

                //玩家参与项目，给庄家APP推送消息
                this.pushAppMessge(taskId,taskLotteryVo.getUserId());

                //开奖处理
                this.lotteryProccess(taskId,false,lotteryMessage);

            } else if (channel.equals(ConstantInterface.REDIS_KEY_EXPIRED)) {
                LOTTERY_LOGGER.info(String.format("竞猜开奖REDIS键过期通知，频道：%s，参数：%s", channel, lotteryMessage));
                String key = lotteryMessage.toString();
                if (!StringUtils.startsWithIgnoreCase(key, ConstantInterface.REDIS_KEY_LOTTERY_PREFIX)) {
                    //不是开奖用的键 过期不处理
                    return;
                }
                taskId = Long.parseLong(StringUtils.substringAfter(key, ConstantInterface.REDIS_KEY_LOTTERY_PREFIX));

                //开奖处理
                this.lotteryProccess(taskId,true,lotteryMessage);
            } else if (channel.equals("CHAT_MESSAGE")){
                //聊天室消息推送，给庄家的APP推送消息
                //TODO
                System.out.println("channel:"+channel + " message:" + lotteryMessage);
                return;
            } else {
                System.out.println("channel:"+channel + " message:" + lotteryMessage);
                LOTTERY_LOGGER.info(String.format("消息频道错误你丫逗我玩呢，频道：%s，参数：%s", channel, lotteryMessage));
                return;
            }
        }catch (Exception e){
            LOTTERY_LOGGER.error(String.format("开奖队列监听 出现未处理异常，频道：%s，参数：%s", channel, lotteryMessage),e);
            return;
        }
    }

    /**
     * 开奖处理
     * @param taskId
     * @param mustLottery
     * @param lotteryMessage
     */
    private void lotteryProccess(long taskId,boolean mustLottery,Serializable lotteryMessage){

        RedisTemplate<String, Object> redisTemplate = (RedisTemplate<String, Object>)SpringContextUtil.getBean("redisTemplate");
        RedisLockUtil lock = new RedisLockUtil(redisTemplate, "LOTTERY_TASK_"+String.valueOf(taskId), 10000, 20000);
        try {
            if(lock.lock()) {
                //开奖处理 项目ID
                ////竞猜项目所属用户
                //final long userId = taskLotteryVo.getUserId();
                ////开奖处理状态 必须传TaskStatusEnum.Complete
                //final TaskStatusEnum taskStatus = CodeEnumUtil.codeOf(TaskStatusEnum.class, taskLotteryVo.getTaskStatus());

                // 开奖逻辑处理
                LotteryResultVo rtn = taskService.runLotteryProcess(taskId,mustLottery);
                if (null == rtn) {
                    return;
                }

                //异步通知开奖结果
                Executor executor = Executors.newSingleThreadExecutor();
                executor.execute(new Runnable() {
                    public void run() {
                        //开奖业务与成功通知区分开，不然redis连不上会造成事务一直不提交
                        taskService.runLotterySuccessNotify(rtn);
                        return;
                    }
                });
            }
        }catch (ApiException e) {
            e.printStackTrace();
            LOTTERY_LOGGER.error(String.format("开奖处理失败，原因：%s，参数：%s",e.getMessage(),lotteryMessage),e);
            //throw e;
        }catch (Exception e){
            e.printStackTrace();
            LOTTERY_LOGGER.error(String.format("开奖处理失败，原因：%s, 参数：%s",e.getMessage(),lotteryMessage),e);
            throw new ApiException(ErrorCodeEnum.SystemError);
        } finally {
            //为了让分布式锁的算法更稳键些，持有锁的客户端在解锁之前应该再检查一次自己的锁是否已经超时，再去做DEL操作，因为可能客户端因为某个耗时的操作而挂起，
            //操作完的时候锁因为超时已经被别人获得，这时就不必解锁了。 ————这里没有做
            lock.unlock();
        }
    }

    /**
     * 玩家下注，APP推送给庄家
     * @param taskId 竞猜项目
     * @param clientId 玩家ID
     * @return
     */
    private boolean pushAppMessge(long taskId,long clientId){
        boolean rtn = false;
        RedisTemplate<String, Object> redisTemplate = (RedisTemplate<String, Object>)SpringContextUtil.getBean("redisTemplate");
        RedisLockUtil lock = new RedisLockUtil(redisTemplate, "LOTTERY_APP_PUSH:BETTING_"+String.valueOf(taskId), 10000, 20000);
        try {
            if(lock.lock()) {
                //redis设置30秒过期key,如果key存在 在不推送，key不存在说明已经超过30秒，那再次推送给庄家
                JedisPool jedisPool = (JedisPool) SpringContextUtil.getBean("jedisPool");
                String key = ConstantInterface.REDIS_KEY_PUSH_LOTTERY + String.valueOf(taskId);
                Jedis jedis = jedisPool.getResource();
                jedis.select(0);

                if (!jedis.exists(key)) {
                    Task t = taskService.selectById(taskId);
                    if (null == t) {
                        LOTTERY_LOGGER.error(String.format("玩家下注APP推送失败，原因：竞猜项目【%s】不存在", taskId));
                        return false;
                    }
                    ClientEntity dealer = clientService.selectById(t.getUserId());
                    if (!StringUtils.isEmpty(dealer.getDeviceToken())) {

                        ClientEntity client = clientService.selectById(clientId);
                        if (null == client) {
                            LOTTERY_LOGGER.error(String.format("玩家下注APP推送失败，原因：参与玩家【%s】不存在", clientId));
                            return false;
                        }
                        String clientName = StringUtils.isEmpty(client.getNickname()) ? client.getPhonenumber() : client.getNickname();

                        String content = String.format("%s参与了你发布的竞猜，快去看看吧", clientName);
                        org.json.JSONObject xgResult = XingeUtil.pushSignl(dealer.getDeviceType(), dealer.getDeviceToken(), content,taskId);

                        if(null != xgResult && xgResult.getInt("ret_code") ==0) {
                            LOTTERY_LOGGER.info(String.format("玩家【%s】参与竞猜项目【%s】，给庄家推送成功！",clientId,taskId));
                            jedis.setex(key, 30, content);
                        }else{
                            LOTTERY_LOGGER.error(String.format("玩家【%s】参与竞猜项目【%s】，给庄家推送失败！原因：%s",clientId,taskId,xgResult.getString("err_msg")));
                        }
                    }
                }

                rtn = true;
            }
        }catch (ApiException e) {
            LOTTERY_LOGGER.error(String.format("玩家【%s】参与竞猜项目【%s】，给庄家推送失败！原因：%s",clientId,taskId,e.getMessage()),e);
        }catch (Exception e){
            LOTTERY_LOGGER.error(String.format("玩家【%s】参与竞猜项目【%s】，给庄家推送失败！原因：%s",clientId,taskId,e.getMessage()),e);
        } finally {
            //为了让分布式锁的算法更稳键些，持有锁的客户端在解锁之前应该再检查一次自己的锁是否已经超时，再去做DEL操作，因为可能客户端因为某个耗时的操作而挂起，
            //操作完的时候锁因为超时已经被别人获得，这时就不必解锁了。 ————这里没有做
            lock.unlock();
        }
        return rtn;
    }
}
