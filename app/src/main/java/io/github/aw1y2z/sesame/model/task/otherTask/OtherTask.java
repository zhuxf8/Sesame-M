package io.github.aw1y2z.sesame.model.task.otherTask;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.util.JsonUtil;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;

/**
 * 「其他」分组：支付宝里不属于森林/庄园/新村等主场景的零散权益任务。
 *
 * <p>当前只有「赚工分」一项，移植自女神逆天版（yaohuoX）的 {@code JiaoFeiCard}。
 * 原版把十几项零散任务塞在同一个「其他任务」模块里，这里只搬用户要的那一项，
 * 其余（黄金票/车神卡/红包雨/消费金…）本仓库在会员、经营等分组已有对应实现，不重复搬。
 *
 * <p>开关结构对齐原版：模块总开关「开启其他任务」+ 子项开关「赚工分」。
 * 子项还额外挂在总开关的依赖上，总开关关掉时子项在界面上也不显示，避免误解。
 */
public class OtherTask extends ModelTask {

    private static final String TAG = OtherTask.class.getSimpleName();

    /** 赚工分每日执行一次即可：任务列表按小时刷新，重复跑只会拿到「已完成」的应答 */
    private static final String FLAG_JIAOFEI = "OtherTask::jiaoFeiCard";

    private BooleanModelField jiaoFeiCard;

    private IntegerModelField jiaoFeiCardInterval;

    @Override
    public String getName() {
        return "其他";
    }

    /** 总开关文案对齐女神版的「开启其他任务」，避免显示成「开启其他」 */
    @Override
    public String getEnableFieldName() {
        return "开启其他任务";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.OTHER;
    }

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(jiaoFeiCard = new BooleanModelField("jiaoFeiCard", "赚工分", false));
        modelFields.addField(jiaoFeiCardInterval = new IntegerModelField("jiaoFeiCardInterval", "赚工分 | 任务间隔(毫秒)", 2000, 0, 60000).setDependsOn("jiaoFeiCard"));
        return modelFields;
    }

    @Override
    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.i("任务暂停⏸️其他:当前为仅收能量时间");
            return false;
        }
        if (jiaoFeiCard != null && jiaoFeiCard.getValue()) {
            return true;
        }
        return false;
    }

    @Override
    public void run() {
        try {
            if (jiaoFeiCard != null && jiaoFeiCard.getValue()) {
                runJiaoFeiCard();
            }
        } catch (Throwable th) {
            Log.err(TAG, "run err:", th);
        }
    }

    /**
     * 赚工分：查任务列表 → 逐个领取 → 提交完成。
     *
     * <p>只做一次是因为服务端按小时结算，重复提交会返回已完成；
     * 需要重试时用 Status 的日标记兜住，避免一天里反复打接口。
     */
    private void runJiaoFeiCard() {
        if (Status.hasFlagToday(FLAG_JIAOFEI)) {
            return;
        }
        try {
            String resp = OtherTaskRpcCall.jiaoFeiCardQueryTask();
            if (resp == null || resp.isEmpty()) {
                Log.i(TAG, "赚工分:查询无响应");
                return;
            }
            JSONObject jo = new JSONObject(resp);
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONArray taskList = (JSONArray) JsonUtil.getValueByPathObject(jo,
                    "components." + "independent_component_task_reward_01961455_independent_component_task_reward_query"
                            + ".content.playTaskOrderInfoList");
            if (taskList == null) {
                Log.i(TAG, "赚工分:无任务列表");
                Status.flagToday(FLAG_JIAOFEI);
                return;
            }
            int done = 0;
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.getJSONObject(i);
                String status = task.optString("taskStatus");
                String advanceType = task.optString("advanceType");
                String code = task.optString("code");
                // finish = 已完成；eventPush = 事件推送型（不能主动提交）
                if ("finish".equals(status) || "eventPush".equals(advanceType) || code.isEmpty()) {
                    continue;
                }
                if (doJiaoFeiCardTask(code)) {
                    done++;
                }
                TimeUtil.sleep(jiaoFeiCardInterval == null ? 2000 : jiaoFeiCardInterval.getValue());
            }
            Log.other("赚工分🪙本轮完成" + done + "个任务");
            Status.flagToday(FLAG_JIAOFEI);
        } catch (Throwable th) {
            Log.err(TAG, "runJiaoFeiCard err:", th);
        }
    }

    /** 单个任务：apply 拿 recordNo → process 提交。返回是否真的拿到工分。 */
    private boolean doJiaoFeiCardTask(String code) {
        try {
            String applyResp = OtherTaskRpcCall.jiaoFeiCardApplyTask(code);
            if (applyResp == null || applyResp.isEmpty()) {
                return false;
            }
            JSONObject applyJo = new JSONObject(applyResp);
            if (!MessageUtil.checkSuccess(TAG, applyJo)) {
                return false;
            }
            String recordNo = JsonUtil.getValueByPath(applyJo,
                    "components." + "independent_component_task_reward_01961455_independent_component_task_reward_apply"
                            + ".content.claimedTask.recordNo");
            if (recordNo == null || recordNo.isEmpty()) {
                return false;
            }
            String processResp = OtherTaskRpcCall.jiaoFeiCardProcessTask(code, recordNo);
            if (processResp == null || processResp.isEmpty()) {
                return false;
            }
            JSONObject processJo = new JSONObject(processResp);
            if (!MessageUtil.checkSuccess(TAG, processJo)) {
                return false;
            }
            JSONObject displayInfo = (JSONObject) JsonUtil.getValueByPathObject(processJo,
                    "components." + "independent_component_task_reward_01961455_independent_component_task_reward_process"
                            + ".content.processedTask.displayInfo");
            if (displayInfo != null) {
                Log.other("赚工分🪙[" + displayInfo.optString("activityName") + "]"
                        + displayInfo.optString("activityValue") + displayInfo.optString("activitySubTitle"));
            }
            return true;
        } catch (Throwable th) {
            Log.err(TAG, "doJiaoFeiCardTask err:", th);
            return false;
        }
    }
}
