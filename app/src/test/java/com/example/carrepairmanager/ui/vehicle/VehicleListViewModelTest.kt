package com.example.carrepairmanager.ui.vehicle

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.example.carrepairmanager.data.AppSettingsRepository
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.local.CarRepairDatabase
import com.example.carrepairmanager.data.local.entity.Vehicle
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 使用真实 Room 和可暂停的查询线程，验证慢查询时输入与结果不会错配。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VehicleListViewModelTest {
    private lateinit var database: CarRepairDatabase
    private lateinit var repository: CarRepairRepository
    private lateinit var settings: AppSettingsRepository
    private lateinit var queryExecutor: ExecutorService
    private lateinit var scope: CoroutineScope
    private val viewModels = ViewModelStore()

    /** 单线程查询可模拟磁盘读取延迟；测试数据库不接触真实档案。 */
    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        queryExecutor = Executors.newSingleThreadExecutor()
        database = Room.inMemoryDatabaseBuilder(application, CarRepairDatabase::class.java)
            .setQueryExecutor(queryExecutor).build()
        repository = CarRepairRepository(database)
        settings = AppSettingsRepository(application)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    /** 先取消页面观察，再释放数据库与测试线程，避免遗留后台读取。 */
    @After
    fun tearDown() {
        viewModels.clear()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        queryExecutor.shutdownNow()
        assertTrue(queryExecutor.awaitTermination(5, TimeUnit.SECONDS))
    }

    /** 即使新查询被阻塞，新关键词下也不能展示并允许点击旧查询的车辆。 */
    @Test
    fun changingQueryClearsPreviousResultsBeforeSlowReadCompletes() {
        val ids = runBlocking {
            listOf(repository.createVehicle(Vehicle(plateNumber = "冀A11111")),
                repository.createVehicle(Vehicle(plateNumber = "冀B22222")))
        }
        val model = observeModel()
        awaitState(model) { !it.loading && it.entries.size == 2 }
        model.setQuery("11111")
        awaitState(model) { !it.loading && it.entries.map { row -> row.vehicle.id } == listOf(ids[0]) }

        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        queryExecutor.execute { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        try {
            model.setQuery("22222")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("22222", model.state.value.filters.query)
            assertTrue("新关键词下仍显示旧结果：" + model.state.value, model.state.value.entries.isEmpty())
            assertTrue(model.state.value.loading)
            assertFalse(model.state.value.hasMore)
        } finally { release.countDown() }
        awaitState(model) { !it.loading && it.entries.map { row -> row.vehicle.id } == listOf(ids[1]) }
    }

    /** 连续修改输入只采用最新结果，清空后恢复全部车辆且不沿用旧关键词卡片。 */
    @Test
    fun rapidInputAndClearUseTheLatestQuery() {
        val ids = runBlocking {
            listOf(repository.createVehicle(Vehicle(plateNumber = "冀A11111")),
                repository.createVehicle(Vehicle(plateNumber = "冀B22222")))
        }
        val model = observeModel()
        awaitState(model) { !it.loading && it.entries.size == 2 }
        model.setQuery("11111")
        shadowOf(Looper.getMainLooper()).idle()
        model.setQuery("22222")
        shadowOf(Looper.getMainLooper()).idle()
        model.setQuery("找不到")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("找不到", model.state.value.filters.query)
        assertTrue(model.state.value.entries.isEmpty())
        awaitState(model) { !it.loading && it.entries.isEmpty() }
        model.setQuery("22222")
        awaitState(model) { !it.loading && it.entries.map { row -> row.vehicle.id } == listOf(ids[1]) }
        model.setQuery("")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", model.state.value.filters.query)
        awaitState(model) { !it.loading && it.entries.size == 2 }
    }

    /** 分页条件变化回到首批，品牌和归档条件组合生效，并可从 SavedStateHandle 重建。 */
    @Test
    fun paginationFiltersAndSavedStateWorkTogether() {
        val ids = runBlocking {
            (0 until 56).map { index ->
                repository.createVehicle(Vehicle(plateNumber = "冀A" + index.toString().padStart(5, '0'),
                    brand = if (index % 2 == 0) "宝马" else "丰田"))
            }
        }
        val savedState = SavedStateHandle()
        val model = observeModel(savedState)
        awaitState(model) { !it.loading && it.entries.size == 50 && it.hasMore }
        model.loadMore()
        awaitState(model) { !it.loading && it.entries.size == 56 && !it.hasMore }
        assertEquals(100, savedState.get<Int>("limit"))
        model.setQuery("000")
        awaitState(model) { !it.loading && it.entries.size == 50 && it.hasMore }
        assertEquals(50, model.state.value.filters.limit)
        model.setBrand("宝马")
        awaitState(model) { !it.loading && it.entries.size == 28 }
        runBlocking { repository.setVehicleArchived(ids[0], true) }
        awaitState(model) { !it.loading && it.entries.size == 27 }
        model.showArchived(true)
        awaitState(model) { !it.loading && it.entries.size == 28 }

        val restoredState = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) })
        val restored = observeModel(restoredState)
        awaitState(restored) { !it.loading && it.entries.size == 28 }
        assertEquals(VehicleFilters("000", "宝马", true, 50), restored.state.value.filters)
        assertEquals(model.state.value.entries.map { it.vehicle.id }, restored.state.value.entries.map { it.vehicle.id })
    }

    /** 真实订阅启动 WhileSubscribed 状态流，ViewModelStore 用于结束页面生命周期。 */
    private fun observeModel(savedState: SavedStateHandle = SavedStateHandle()): VehicleListViewModel =
        VehicleListViewModel(repository, settings, savedState).also { model ->
            viewModels.put("vehicles", model)
            scope.launch { model.state.collect {} }
        }

    /** Android 主线程使用可控时钟，后台 Room 返回后再断言页面快照。 */
    private fun awaitState(model: VehicleListViewModel, predicate: (VehicleListUiState) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (predicate(model.state.value)) return
            Thread.sleep(10)
        }
        throw AssertionError("未得到预期车辆列表状态：${model.state.value}")
    }
}
