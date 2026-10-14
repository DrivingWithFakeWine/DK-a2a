package org.ruoyi.workflow.workflow.node.switcher;

import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.service.WorkflowNodeService;
import org.ruoyi.workflow.workflow.WfNodeState;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 针对 SwitcherNode 条件取值兜底覆盖 bug 的回归测试。
 *
 * 背景：getValueFromInputs 第 2 步已按参数名从上游节点运行时 IO 取到值后，
 * 兜底函数 findParamValueInNode（为"开始节点参数名错误"场景设计）曾无条件覆盖该值，
 * 导致条件分支恒走默认分支。修复后兜底仅在未取到值（result == null）时执行。
 */
@Tag("dev")
@DisplayName("条件分支节点参数取值（兜底覆盖 bug 回归）")
class SwitcherNodeGetValueFromInputsTest {

    private static final String START_UUID = "start-node-uuid";

    /**
     * 开始节点参数定义：定义了 environment 字段（与线上问题场景一致）
     */
    private static final String ENV_CONFIG =
        "{\"user_inputs\":[{\"name\":\"environment\",\"title\":\"发布环境\",\"type\":1,\"required\":true}]}";

    private WfState wfState;
    private SwitcherNode switcherNode;
    private Method getValueFromInputs;
    private Method evaluateCondition;

    @BeforeEach
    void setUp() throws Exception {
        // SwitcherNode 的静态字段在类初始化时会执行 SpringUtils.getBean(WorkflowNodeService.class)，
        // 注册一个带 mock 单例的最小 Spring 上下文，使测试无需真实容器即可完成类初始化
        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("workflowNodeService", mock(WorkflowNodeService.class));
        context.refresh();
        // hutool SpringUtil 的 setApplicationContext 是实例方法（ApplicationContextAware 回调），内部写入静态字段
        new SpringUtil().setApplicationContext(context);

        wfState = mock(WfState.class);
        switcherNode = new SwitcherNode(
            mock(WorkflowComponent.class), mock(WorkflowNode.class), wfState, mock(WfNodeState.class));

        getValueFromInputs = SwitcherNode.class.getDeclaredMethod(
            "getValueFromInputs", String.class, String.class, List.class);
        getValueFromInputs.setAccessible(true);
        evaluateCondition = SwitcherNode.class.getDeclaredMethod(
            "evaluateCondition", SwitcherCase.Condition.class, List.class);
        evaluateCondition.setAccessible(true);
    }

    @Test
    @DisplayName("回归：上游 IO 已按名字取到值时，input 兜底不得覆盖（修复前返回'本次变更'的值）")
    void existingRuntimeValueMustNotBeOverwritten() throws Exception {
        stubDefinitionLookup(nodeWithConfig(ENV_CONFIG));
        when(wfState.getIOByNodeUuid(START_UUID)).thenReturn(List.of(
            NodeIOData.createByText("output", "本次变更", "优化知识检索超时处理"),
            NodeIOData.createByText("environment", "发布环境", "prod"),
            NodeIOData.createByText("version", "发布版本", "v1.8.0")));
        List<NodeIOData> currentInputs = List.of(
            NodeIOData.createByText("input", "default", "优化知识检索超时处理"));

        String actual = invokeGetValueFromInputs("environment", currentInputs);

        assertEquals("prod", actual);
    }

    @Test
    @DisplayName("端到端：environment=prod 时条件等于 prod 判定为真（线上问题场景）")
    void conditionMatchesWhenEnvironmentIsProd() throws Exception {
        stubDefinitionLookup(nodeWithConfig(ENV_CONFIG));
        when(wfState.getIOByNodeUuid(START_UUID)).thenReturn(List.of(
            NodeIOData.createByText("output", "本次变更", "优化知识检索超时处理"),
            NodeIOData.createByText("environment", "发布环境", "prod"),
            NodeIOData.createByText("version", "发布版本", "v1.8.0")));
        List<NodeIOData> currentInputs = List.of(
            NodeIOData.createByText("input", "default", "优化知识检索超时处理"));

        boolean matched = (Boolean) evaluateCondition.invoke(switcherNode,
            condition("environment", "=", "prod"), currentInputs);

        assertTrue(matched);
    }

    @Test
    @DisplayName("修复能力保留：参数名在运行时缺失（被改名）时，仍从 input 兜底取值")
    void fallbackStillRepairsMissingParamName() throws Exception {
        stubDefinitionLookup(nodeWithConfig(ENV_CONFIG));
        // 运行时 IO 里没有名为 environment 的参数（它被改名成了默认通道）
        when(wfState.getIOByNodeUuid(START_UUID)).thenReturn(List.of(
            NodeIOData.createByText("output", "发布环境", "prod"),
            NodeIOData.createByText("version", "发布版本", "v1.8.0")));
        List<NodeIOData> currentInputs = List.of(
            NodeIOData.createByText("input", "default", "prod"));

        String actual = invokeGetValueFromInputs("environment", currentInputs);

        assertEquals("prod", actual);
    }

    @Test
    @DisplayName("防御：字段名拼写错误时返回 null，不产生错误的命中值")
    void typoParamNameYieldsNull() throws Exception {
        stubDefinitionLookup(nodeWithConfig(ENV_CONFIG));
        when(wfState.getIOByNodeUuid(START_UUID)).thenReturn(List.of(
            NodeIOData.createByText("environment", "发布环境", "prod")));

        String actual = invokeGetValueFromInputs("enviroment", List.of());

        assertNull(actual);
    }

    private String invokeGetValueFromInputs(String paramName, List<NodeIOData> currentInputs)
        throws Exception {
        return (String) getValueFromInputs.invoke(switcherNode, START_UUID, paramName, currentInputs);
    }

    private static SwitcherCase.Condition condition(String paramName, String operator, String value) {
        SwitcherCase.Condition condition = new SwitcherCase.Condition();
        condition.setNodeUuid(START_UUID);
        condition.setNodeParamName(paramName);
        condition.setOperator(operator);
        condition.setValue(value);
        return condition;
    }

    private static WorkflowNode nodeWithConfig(String inputConfig) {
        WorkflowNode node = new WorkflowNode();
        node.setInputConfig(inputConfig);
        return node;
    }

    @SuppressWarnings("unchecked")
    private void stubDefinitionLookup(WorkflowNode definitionNode) {
        LambdaQueryChainWrapper<WorkflowNode> chain = mock(LambdaQueryChainWrapper.class);
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.one()).thenReturn(definitionNode);
        WorkflowNodeService nodeService =
            (WorkflowNodeService) SpringUtil.getBean("workflowNodeService");
        when(nodeService.lambdaQuery()).thenReturn(chain);
    }
}
