package com.example.carrepairmanager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真机验证导航、返回栈及 Activity 重建，覆盖项目骨架的主要风险。 */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** 每项测试在新建的 Activity 中开始，确认启动首页后再执行其他操作。 */
    @Before
    fun openHome() {
        // 等待初始 Compose 页面创建，再确认全新启动落在首页。
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("首页") and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("最近到店").assertIsDisplayed()
        composeRule.onNode(hasText("首页") and hasClickAction()).assertIsSelected()
    }

    /** 重复点击标签后返回首页，避免重复目的地造成返回栈堆积。 */
    @Test
    fun tabsNavigateAndBackReturnsHome() {
        composeRule.onNodeWithText("最近到店").assertIsDisplayed()
        repeat(3) {
            composeRule.onNode(hasText("车辆") and hasClickAction()).performClick()
        }
        composeRule.onNodeWithText("显示已归档车辆").assertIsDisplayed()
        composeRule.onNode(hasText("车辆") and hasClickAction()).assertIsSelected()
        composeRule.onNode(hasText("设置") and hasClickAction()).performClick()
        composeRule.onNodeWithText("应用设置").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithText("最近到店").assertIsDisplayed()
        composeRule.onNode(hasText("首页") and hasClickAction()).assertIsSelected()
    }

    /** 配置变化重建 Activity 后应保留当前标签，而非跳回默认首页。 */
    @Test
    fun selectedTabSurvivesActivityRecreation() {
        composeRule.onNode(hasText("车辆") and hasClickAction()).performClick()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("显示已归档车辆").assertIsDisplayed()
        composeRule.onNode(hasText("车辆") and hasClickAction()).assertIsSelected()
    }

    /** 点击首页必须回到真实首页，不能恢复与首页关联的车辆或设置栈。 */
    @Test
    fun homeTabCanBeReachedAfterRepeatedTabSwitches() {
        repeat(3) {
            composeRule.onNode(hasText("车辆") and hasClickAction()).performClick()
            composeRule.onNodeWithText("显示已归档车辆").assertIsDisplayed()
            composeRule.onNode(hasText("设置") and hasClickAction()).performClick()
            composeRule.onNodeWithText("应用设置").assertIsDisplayed()
            composeRule.onNode(hasText("首页") and hasClickAction()).performClick()
            composeRule.onNodeWithText("最近到店").assertIsDisplayed()
            composeRule.onNode(hasText("首页") and hasClickAction()).assertIsSelected()
        }
    }

    /** 暂停动画时钟，在页面切换后的前两帧检查旧文字已移除，覆盖淡化重叠。 */
    @Test
    fun pageSwitchRemovesPreviousTextWithoutFade() {
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNode(hasText("设置") and hasClickAction()).performClick()
            composeRule.mainClock.advanceTimeBy(32)
            composeRule.onNodeWithText("应用设置").assertIsDisplayed()
            composeRule.onNodeWithText("最近到店").assertDoesNotExist()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }
}
