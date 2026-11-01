package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectAndCountModelField;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.entity.CooperateUser;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.model.task.protectEcology.CooperateRpcCall;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.CooperationIdMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 老版合种（移植自女神逆天版 3.5.5 的 {@code AntCooperate}，逐行对齐）。
 *
 * <p><b>与「保护 → 新版合种」是两套东西，不要合并。</b>
 * <ul>
 *   <li><b>老版合种（本类）</b>：走 {@code queryUserCooperatePlantList} 列出账号参与的全部合种，
 *       勾选<b>小队名称</b>并设置每日浇水克数，逐个小队浇。</li>
 *   <li><b>新版合种（ProtectEcology）</b>：走 {@code COOPERATE_PLANT} 模板，只能浇当前那棵树。</li>
 *   <li><b>组队合种 / 真爱合种（AntForestV2）</b>：2024 年后支付宝推的组队玩法，走 {@code teamHomeResult}。</li>
 * </ul>
 * 三者接口、配置、服务端记账口径都不同，所以独立成模块，名字里带「老版」以免混淆。
 *
 * <p>按用户要求剔除了女神版的四项配置（其余逻辑原样保留）：
 * 「浇水总量限制列表」「限制关键字用户名(我还没好)」「限制关键字」「限制关键字2」。
 */
public class OldCooperate extends ModelTask {

    private static final String TAG = OldCooperate.class.getSimpleName();

    /**
     * 当日已浇量（克）的持久化前缀，key = 前缀 + cooperationId。
     * <p>等价于女神版的 {@code Status.setCompletedDaysByUser(CooperateWater, id, n)}：
     * 按 id 记当日累计，随 status.json 的每日重置清空。M 版没有 CompletedKeyEnum 那套，
     * 用 intFlagLogList 实现同样语义。
     */
    private static final String FLAG_PREFIX = "OldCooperate::";

    private BooleanModelField oldCooperateWater;

    private SelectAndCountModelField oldCooperateWaterList;

    @Override
    public String getName() {
        return "老版合种";
    }

    /** 总开关文案：女神版原文是「开启合种」，这里加「老版」以免与新版合种混淆 */
    @Override
    public String getEnableFieldName() {
        return "开启老版合种";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.FOREST;
    }

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(oldCooperateWater = new BooleanModelField("oldCooperateWater", "老版合种 | 浇水", false));
        // 勾选的是小队名称，填的是该小队每日要浇多少克；上限 1000g（与女神版 cooperateWaterList 一致）
        modelFields.addField(oldCooperateWaterList = new SelectAndCountModelField(
                "oldCooperateWaterList", "老版合种 | 浇水列表", new LinkedHashMap<>(),
                CooperateUser::getList, "请填写浇水克数(每日，最高1000g)", 0, 1000)
                .setDependsOn("oldCooperateWater"));
        return modelFields;
    }

    @Override
    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.i("任务暂停⏸️老版合种:当前为仅收能量时间");
            return false;
        }
        return oldCooperateWater != null && oldCooperateWater.getValue();
    }

    /** 与女神版 {@code AntCooperate.run()} 逐段对齐 */
    @Override
    public void run() {
        try {
            if (oldCooperateWater == null || !oldCooperateWater.getValue()) {
                return;
            }
            Map<String, Integer> targets = oldCooperateWaterList.getValue();
            // 一个都没配、但已经知道有合种 → 直接返回，不必打接口（女神版同款短路）
            if ((targets == null || targets.isEmpty()) && !CooperationIdMap.getMap().isEmpty()) {
                return;
            }

            String resp = CooperateRpcCall.queryUserCooperatePlantList();
            if (resp == null) {
                TimeUtil.sleep(1000);
                resp = CooperateRpcCall.queryUserCooperatePlantList();
            }
            if (resp == null || resp.isEmpty()) {
                Log.i(TAG, "queryUserCooperatePlantList 无响应");
                return;
            }
            JSONObject jo = new JSONObject(resp);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }

            int userCurrentEnergy = jo.optInt("userCurrentEnergy", 0);
            JSONArray cooperatePlants = jo.optJSONArray("cooperatePlants");
            if (cooperatePlants == null) {
                CooperationIdMap.save(UserIdMap.getCurrentUid());
                return;
            }

            String userId = UserIdMap.getCurrentUid();
            for (int i = 0; i < cooperatePlants.length(); i++) {
                JSONObject plant = cooperatePlants.getJSONObject(i);
                String cooperationId = plant.optString("cooperationId");
                if (cooperationId.isEmpty()) {
                    continue;
                }
                // 列表项不带 name 时单独查详情（女神版同款）
                if (!plant.has("name")) {
                    JSONObject detail = queryPlant(cooperationId);
                    if (detail == null) {
                        continue;
                    }
                    plant = detail;
                }
                String name = plant.optString("name", cooperationId);
                int waterDayLimit = plant.optInt("waterDayLimit", 0);
                CooperationIdMap.add(cooperationId, name);

                if (targets == null || targets.isEmpty()) {
                    continue;
                }
                Integer daily = targets.get(cooperationId);
                if (daily == null || daily == 0) {
                    // 未勾选该小队，静默跳过
                    continue;
                }
                // 当日已浇量已达目标则跳过（女神版 getCompletedDaysByUser 同款）
                if (getWateredToday(cooperationId) >= daily) {
                    continue;
                }
                int waterNum = daily;
                if (waterDayLimit > 0) {
                    waterNum = Math.min(waterNum, waterDayLimit);
                }
                if (waterNum > userCurrentEnergy) {
                    waterNum = userCurrentEnergy;
                }
                if (waterNum > 0) {
                    water(userId, cooperationId, waterNum, name);
                    TimeUtil.sleep(300);
                }
            }
            CooperationIdMap.save(userId);
        } catch (Throwable th) {
            Log.err(TAG, "老版合种 run err:", th);
        }
    }

    /** 查合种详情；失败返回 null */
    private static JSONObject queryPlant(String cooperationId) {
        try {
            JSONObject jo = new JSONObject(CooperateRpcCall.queryCooperatePlant(cooperationId));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return null;
            }
            return jo.optJSONObject("cooperatePlant");
        } catch (Throwable th) {
            Log.err(TAG, "queryPlant err:", th);
            return null;
        }
    }

    /** 当日已浇量（克），对应女神版 {@code getCompletedDaysByUser} 的读取侧 */
    private static int getWateredToday(String cooperationId) {
        return Status.getIntFlagToday(FLAG_PREFIX + cooperationId);
    }

    private static boolean water(String userId, String cooperationId, int energyCount, String name) {
        try {
            JSONObject jo = new JSONObject(CooperateRpcCall.cooperateWater(userId, cooperationId, energyCount));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            // 对应女神版 setCompletedDaysByUser：浇成功才累加，避免失败也记账
            int already = Status.getIntFlagToday(FLAG_PREFIX + cooperationId);
            Status.setIntFlagToday(FLAG_PREFIX + cooperationId, already + energyCount);
            String barrage = jo.optString("barrageText");
            Log.forest("老版合种🚿[" + name + "]#" + energyCount + "g" + (barrage.isEmpty() ? "" : " " + barrage));
            return true;
        } catch (Throwable th) {
            Log.err(TAG, "water err:", th);
            return false;
        }
    }
}
