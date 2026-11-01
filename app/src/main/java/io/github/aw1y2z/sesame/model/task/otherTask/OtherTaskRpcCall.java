package io.github.aw1y2z.sesame.model.task.otherTask;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.hook.ApplicationHook;

/**
 * 「其他任务」用到的 programInvoke 系列 RPC。
 *
 * <p>这套接口不是森林/庄园那种 H5 单方法 RPC，而是支付宝活动平台（imasp program）的
 * 组件式调用：一个请求里带 {@code components} 字典，键是组件名，值是该组件的入参。
 * 所以每个业务动作都要自己拼 body，不能复用 AntForestRpcCall 那套。
 *
 * <p>移植自女神逆天版（yaohuoX）的 {@code BaseTaskRpcCall.programInvoke} + {@code JiaoFeiCard}，
 * 参数与组件名逐字对齐，不做"顺手优化"——组件名里那串数字是活动 ID，改一个字符就 404。
 */
public class OtherTaskRpcCall {

    /** 赚工分活动（职业权益中心 job-right-center）的 operationParamIdentify */
    private static final String JOB_RIGHT_OPERATION_ID = "independent_component_program2024121902034600";

    /** 赚工分：任务列表查询组件 */
    private static final String JIAOFEI_QUERY_COMPONENT =
            "independent_component_task_reward_01961455_independent_component_task_reward_query";

    /** 赚工分：领取任务组件 */
    private static final String JIAOFEI_APPLY_COMPONENT =
            "independent_component_task_reward_01961455_independent_component_task_reward_apply";

    /** 赚工分：提交完成组件 */
    private static final String JIAOFEI_PROCESS_COMPONENT =
            "independent_component_task_reward_01961455_independent_component_task_reward_process";

    private OtherTaskRpcCall() {
    }

    /**
     * 通用 programInvoke 入口。
     *
     * @param components 组件名字典，键为组件名、值为该组件的入参 JSONObject
     * @return 原始响应字符串；拼接失败返回 null
     */
    public static String programInvoke(JSONObject components) {
        try {
            JSONObject body = new JSONObject();
            body.put("channel", "job-right-center");
            body.put("operationParamIdentify", JOB_RIGHT_OPERATION_ID);
            body.put("source", "job-right-center");
            body.put("components", components);
            return ApplicationHook.requestString("alipay.imasp.program.programInvoke",
                    new JSONArray().put(body).toString());
        } catch (Throwable th) {
            return null;
        }
    }

    /** 赚工分：查询可做任务列表 */
    public static String jiaoFeiCardQueryTask() {
        try {
            JSONObject components = new JSONObject();
            components.put(JIAOFEI_QUERY_COMPONENT, new JSONObject());
            return programInvoke(components);
        } catch (Throwable th) {
            return null;
        }
    }

    /** 赚工分：领取单个任务（拿 recordNo） */
    public static String jiaoFeiCardApplyTask(String code) {
        try {
            JSONObject compBody = new JSONObject();
            compBody.put("code", code);
            JSONObject components = new JSONObject();
            components.put(JIAOFEI_APPLY_COMPONENT, compBody);
            return programInvoke(components);
        } catch (Throwable th) {
            return null;
        }
    }

    /** 赚工分：提交任务完成（拿工分） */
    public static String jiaoFeiCardProcessTask(String code, String recordNo) {
        try {
            JSONObject compBody = new JSONObject();
            compBody.put("code", code);
            compBody.put("outBizNo", System.currentTimeMillis());
            compBody.put("recordNo", recordNo);
            JSONObject components = new JSONObject();
            components.put(JIAOFEI_PROCESS_COMPONENT, compBody);
            return programInvoke(components);
        } catch (Throwable th) {
            return null;
        }
    }
}
