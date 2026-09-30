/**
 * EyeCare 20-20-20 护眼助手 - 前端仿真预览逻辑 (纯视觉、零外放、无震动)
 *
 * 核心机制说明：
 * 1. 仅累计亮屏时间：屏幕常亮时秒数递增，锁屏/黑屏时暂停计时；
 * 2. 满周期（支持自定义运行时间：10秒快速测试、10~45分钟预设或自定义输入）自动触发半透明全屏悬浮遮罩；
 * 3. 视频播放暂停联动：在刷抖音等视频时，遮罩弹出自动打断并暂停播放；20秒远眺倒计时结束后平滑淡出并自动恢复播放；
 * 4. 纯视觉静音设计：不调用任何 Web Audio API，不调用 navigator.vibrate。
 */

// ==========================================================================
// 1. 系统常量与全局响应式状态
// ==========================================================================
const SVG_RADIUS = 88;
const SVG_CIRCUMFERENCE = 2 * Math.PI * SVG_RADIUS; // 约 552.92px

const appState = {
  // 屏幕状态：true 为亮屏，false 为锁屏/黑屏
  isScreenOn: true,
  // 当前周期内累计亮屏有效秒数
  screenOnSeconds: 0,
  // 触发遮罩的工作时长阈值（默认 10 秒测试，可选分钟或秒数）
  workCycleDuration: 10,
  // 远眺休息倒计时时长（默认 20 秒）
  restDuration: 20,
  // 遮罩当前是否正在显示中
  isOverlayShowing: false,
  // 遮罩倒计时当前剩余秒数
  remainingSeconds: 20,
  // 倒计时句柄
  overlayTimerId: null,
  // 当前手机模拟场景：'video' (抖音短视频) 或 'article' (深度阅读)
  currentScene: 'video',
  // 是否开启遮罩弹出时自动暂停音视频
  pauseMediaEnabled: true
};

// ==========================================================================
// 2. DOM 元素快速引用
// ==========================================================================
const elements = {
  // 手机模型区域
  phoneScreen: document.getElementById('phoneScreen'),
  statusTime: document.getElementById('statusTime'),
  screenOffMask: document.getElementById('screenOffMask'),
  eyeCareOverlay: document.getElementById('eyeCareOverlay'),
  ringProgress: document.getElementById('ringProgress'),
  countdownSeconds: document.getElementById('countdownSeconds'),
  guidanceTitle: document.getElementById('guidanceTitle'),
  guidanceDesc: document.getElementById('guidanceDesc'),
  guidanceMediaSubtip: document.getElementById('guidanceMediaSubtip'),

  // 场景与模拟应用容器
  tabVideo: document.getElementById('tabVideo'),
  tabArticle: document.getElementById('tabArticle'),
  simulatedVideoContent: document.getElementById('simulatedVideoContent'),
  simulatedAppContent: document.getElementById('simulatedAppContent'),
  videoStatusText: document.getElementById('videoStatusText'),

  // 状态看板
  dispScreenState: document.getElementById('dispScreenState'),
  dispScreenTime: document.getElementById('dispScreenTime'),
  dispTargetTime: document.getElementById('dispTargetTime'),
  dispMediaState: document.getElementById('dispMediaState'),
  dispOverlayState: document.getElementById('dispOverlayState'),
  dispConfiguredCycle: document.getElementById('dispConfiguredCycle'),

  // 动作按钮
  btnToggleScreen: document.getElementById('btnToggleScreen'),
  btnToggleScreenText: document.getElementById('btnToggleScreenText'),
  btnResetTimer: document.getElementById('btnResetTimer'),
  btnTriggerNow: document.getElementById('btnTriggerNow'),

  // 运行时间控制
  presetChips: document.querySelectorAll('.preset-chips-container .chip-btn'),
  inputCustomValue: document.getElementById('inputCustomValue'),
  selectCustomUnit: document.getElementById('selectCustomUnit'),
  btnApplyCustomCycle: document.getElementById('btnApplyCustomCycle'),
  inputRestDuration: document.getElementById('inputRestDuration'),
  valRestDuration: document.getElementById('valRestDuration'),

  // 音视频暂停联动
  checkPauseMedia: document.getElementById('checkPauseMedia'),
  btnToggleScene: document.getElementById('btnToggleScene'),

  // 视觉参数调节输入
  inputOpacity: document.getElementById('inputOpacity'),
  valOpacity: document.getElementById('valOpacity'),
  inputBlur: document.getElementById('inputBlur'),
  valBlur: document.getElementById('valBlur'),
  inputFontScale: document.getElementById('inputFontScale'),
  valFontScale: document.getElementById('valFontScale')
};

// ==========================================================================
// 3. 辅助格式化函数
// ==========================================================================

/**
 * 将秒数格式化为 mm:ss 字符串
 * @param {number} totalSeconds 
 * @returns {string} 格式化后的时间字符串
 */
function formatTime(totalSeconds) {
  const m = Math.floor(totalSeconds / 60).toString().padStart(2, '0');
  const s = (totalSeconds % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
}

/**
 * 格式化周期文本显示（如 "10 秒 (测试)" 或 "20 分钟"）
 * @param {number} seconds 
 * @returns {string}
 */
function formatCycleText(seconds) {
  if (seconds < 60) {
    return `${seconds} 秒 (测试)`;
  }
  const mins = Math.floor(seconds / 60);
  const remainSecs = seconds % 60;
  if (remainSecs > 0) {
    return `${mins}分${remainSecs}秒`;
  }
  return `${mins} 分钟`;
}

/**
 * 实时同步顶部状态栏的时钟显示（HH:mm）
 */
function updateClockTime() {
  const now = new Date();
  const hours = now.getHours().toString().padStart(2, '0');
  const minutes = now.getMinutes().toString().padStart(2, '0');
  if (elements.statusTime) {
    elements.statusTime.textContent = `${hours}:${minutes}`;
  }
}

// ==========================================================================
// 4. 模拟短视频与场景联动逻辑
// ==========================================================================

/**
 * 切换模拟手机的运行场景（抖音短视频 vs 深度阅读）
 * @param {'video'|'article'} scene 
 */
function switchScene(scene) {
  appState.currentScene = scene;

  if (scene === 'video') {
    elements.simulatedVideoContent.classList.remove('hidden');
    elements.simulatedAppContent.classList.add('hidden');
    elements.tabVideo.classList.add('active');
    elements.tabArticle.classList.remove('active');
    elements.btnToggleScene.textContent = '切换为阅读模式';
    updateMediaPlaybackVisual();
  } else {
    elements.simulatedVideoContent.classList.add('hidden');
    elements.simulatedAppContent.classList.remove('hidden');
    elements.tabArticle.classList.add('active');
    elements.tabVideo.classList.remove('active');
    elements.btnToggleScene.textContent = '切换为抖音短视频';
    elements.dispMediaState.textContent = '未播放 (阅读中)';
    elements.dispMediaState.style.color = '#8896aa';
  }
}

/**
 * 更新短视频播放/暂停状态界面的视觉元素
 */
function updateMediaPlaybackVisual() {
  if (appState.currentScene !== 'video') return;

  if (appState.isOverlayShowing && appState.pauseMediaEnabled) {
    // 遮罩显示中且开启了自动暂停：暂停视频与旋转唱片
    elements.simulatedVideoContent.classList.add('paused');
    elements.videoStatusText.textContent = '已自动暂停 (远眺中)';
    elements.dispMediaState.textContent = '已自动暂停 ⏸';
    elements.dispMediaState.style.color = '#ff9f43';
    elements.guidanceMediaSubtip.textContent = '视频已自动暂停 · 倒计时归零后将自动恢复播放';
  } else {
    // 正常播放状态
    elements.simulatedVideoContent.classList.remove('paused');
    elements.videoStatusText.textContent = '短视频正在播放中';
    elements.dispMediaState.textContent = '播放中 (抖音) 🎵';
    elements.dispMediaState.style.color = '#38ef7d';
    elements.guidanceMediaSubtip.textContent = appState.pauseMediaEnabled 
      ? '倒计时归零后将自动淡出关闭' 
      : '未开启媒体打断 · 视频持续播放中';
  }
}

// ==========================================================================
// 5. 遮罩层业务控制器 (纯视觉提示、倒计时、平滑淡出、音视频暂停恢复)
// ==========================================================================

/**
 * 弹出全屏护眼遮罩并启动远眺倒计时
 */
function showEyeCareOverlay() {
  if (appState.isOverlayShowing) return;

  appState.isOverlayShowing = true;
  appState.remainingSeconds = appState.restDuration;

  // 更新面板状态显示
  elements.dispOverlayState.textContent = '护眼远眺提醒中';
  elements.dispOverlayState.style.color = '#38ef7d';

  // 触发视频自动暂停
  updateMediaPlaybackVisual();

  // 显示遮罩 DOM（通过 CSS transition 产生平滑渐变入场）
  elements.eyeCareOverlay.classList.add('visible');
  elements.eyeCareOverlay.setAttribute('aria-hidden', 'false');

  // 初始化倒计时界面
  elements.countdownSeconds.textContent = appState.remainingSeconds;
  updateCircularProgress(appState.remainingSeconds, appState.restDuration);

  // 清理可能遗留的旧定时器
  if (appState.overlayTimerId) {
    clearInterval(appState.overlayTimerId);
  }

  // 启动 1 秒步长的纯视觉倒计时（零外放音频、无任何震动）
  appState.overlayTimerId = setInterval(() => {
    appState.remainingSeconds--;

    if (appState.remainingSeconds <= 0) {
      // 倒计时归零，完成本轮远眺
      elements.countdownSeconds.textContent = '0';
      updateCircularProgress(0, appState.restDuration);
      clearInterval(appState.overlayTimerId);
      appState.overlayTimerId = null;

      // 延迟 400ms 后平滑淡出关闭遮罩并恢复视频播放
      setTimeout(() => {
        hideEyeCareOverlay();
      }, 400);
    } else {
      elements.countdownSeconds.textContent = appState.remainingSeconds;
      updateCircularProgress(appState.remainingSeconds, appState.restDuration);
    }
  }, 1000);
}

/**
 * 平滑淡出并隐藏护眼遮罩，恢复音视频播放，静默开启下一轮亮屏循环
 */
function hideEyeCareOverlay() {
  elements.eyeCareOverlay.classList.remove('visible');
  elements.eyeCareOverlay.setAttribute('aria-hidden', 'true');
  appState.isOverlayShowing = false;

  // 恢复短视频继续播放
  updateMediaPlaybackVisual();

  // 重置累计亮屏秒数，开启下一轮周期
  appState.screenOnSeconds = 0;

  // 恢复面板状态
  elements.dispOverlayState.textContent = '就绪中';
  elements.dispOverlayState.style.color = '';
  updateDashboard();
}

/**
 * 更新 SVG 环形进度条的 stroke-dashoffset 偏移量
 * @param {number} remaining 剩余秒数
 * @param {number} total 总秒数
 */
function updateCircularProgress(remaining, total) {
  if (!elements.ringProgress) return;
  const progressRatio = remaining / total;
  const offset = SVG_CIRCUMFERENCE * (1 - progressRatio);
  elements.ringProgress.style.strokeDashoffset = offset;
}

// ==========================================================================
// 6. 亮屏主计时器循环 (仅在亮屏且非提醒状态下累加)
// ==========================================================================

/**
 * 每秒心跳：仅在亮屏且遮罩未展示时累计亮屏时间
 */
function tickScreenTimer() {
  if (appState.isScreenOn && !appState.isOverlayShowing) {
    appState.screenOnSeconds++;

    // 达到触发阈值时，自动弹出全屏遮罩
    if (appState.screenOnSeconds >= appState.workCycleDuration) {
      showEyeCareOverlay();
    }
  }

  updateDashboard();
}

/**
 * 刷新控制面板上的运行状态数据
 */
function updateDashboard() {
  // 1. 累计亮屏时间
  elements.dispScreenTime.textContent = formatTime(appState.screenOnSeconds);

  // 2. 距离下一次遮罩触发的剩余使用时间倒计时
  if (appState.isOverlayShowing) {
    elements.dispTargetTime.textContent = '远眺休息中...';
  } else {
    const remainToTrigger = Math.max(0, appState.workCycleDuration - appState.screenOnSeconds);
    elements.dispTargetTime.textContent = formatTime(remainToTrigger);
  }

  // 3. 设定周期
  elements.dispConfiguredCycle.textContent = formatCycleText(appState.workCycleDuration);

  // 4. 屏幕状态
  if (appState.isScreenOn) {
    elements.dispScreenState.textContent = '亮屏中 (计时运行)';
    elements.dispScreenState.style.color = '#38ef7d';
  } else {
    elements.dispScreenState.textContent = '已锁屏/黑屏 (计时挂起)';
    elements.dispScreenState.style.color = '#9aa8bc';
  }
}

// ==========================================================================
// 7. 控制面板交互与动态参数绑定
// ==========================================================================

/**
 * 切换模拟手机的熄屏/亮屏状态
 */
function toggleScreenPower() {
  appState.isScreenOn = !appState.isScreenOn;

  if (appState.isScreenOn) {
    elements.screenOffMask.classList.remove('active');
    elements.btnToggleScreenText.textContent = '模拟熄屏/锁屏';
    updateMediaPlaybackVisual();
  } else {
    elements.screenOffMask.classList.add('active');
    elements.btnToggleScreenText.textContent = '模拟点亮屏幕';
    // 熄屏时视频也自动暂停
    if (appState.currentScene === 'video') {
      elements.simulatedVideoContent.classList.add('paused');
      elements.dispMediaState.textContent = '锁屏暂停 ⏸';
    }
  }

  updateDashboard();
}

/**
 * 重置当前累计的亮屏计时
 */
function resetScreenTimer() {
  appState.screenOnSeconds = 0;
  if (appState.isOverlayShowing) {
    if (appState.overlayTimerId) clearInterval(appState.overlayTimerId);
    hideEyeCareOverlay();
  }
  updateDashboard();
}

/**
 * 切换选中的运行周期时长
 * @param {number} seconds 
 */
function setWorkCycle(seconds) {
  appState.workCycleDuration = seconds;
  appState.screenOnSeconds = 0;

  // 更新预设胶囊选中高亮
  elements.presetChips.forEach(chip => {
    const cycle = parseInt(chip.getAttribute('data-cycle'), 10);
    if (cycle === seconds) {
      chip.classList.add('active');
    } else {
      chip.classList.remove('active');
    }
  });

  updateDashboard();
}

/**
 * 绑定所有滑块输入与按钮监听
 */
function bindControlInputs() {
  // 场景切换选项卡
  elements.tabVideo.addEventListener('click', () => switchScene('video'));
  elements.tabArticle.addEventListener('click', () => switchScene('article'));

  // 场景切换按钮（控制面板）
  elements.btnToggleScene.addEventListener('click', () => {
    switchScene(appState.currentScene === 'video' ? 'article' : 'video');
  });

  // 音视频暂停联动开关
  elements.checkPauseMedia.addEventListener('change', (e) => {
    appState.pauseMediaEnabled = e.target.checked;
    updateMediaPlaybackVisual();
  });

  // 预设时长胶囊按钮点击
  elements.presetChips.forEach(chip => {
    chip.addEventListener('click', () => {
      const cycle = parseInt(chip.getAttribute('data-cycle'), 10);
      setWorkCycle(cycle);
    });
  });

  // 自定义时长应用
  elements.btnApplyCustomCycle.addEventListener('click', () => {
    const val = parseInt(elements.inputCustomValue.value, 10);
    const multiplier = parseInt(elements.selectCustomUnit.value, 10);
    if (!isNaN(val) && val > 0) {
      const totalSeconds = val * multiplier;
      setWorkCycle(totalSeconds);
    }
  });

  // 远眺倒计时时长调节
  elements.inputRestDuration.addEventListener('input', (e) => {
    const val = parseInt(e.target.value, 10);
    elements.valRestDuration.textContent = `${val}秒`;
    appState.restDuration = val;
  });

  // 遮罩不透明度
  elements.inputOpacity.addEventListener('input', (e) => {
    const val = parseFloat(e.target.value);
    elements.valOpacity.textContent = val.toFixed(2);
    document.documentElement.style.setProperty('--overlay-opacity', val);
  });

  // 毛玻璃模糊度
  elements.inputBlur.addEventListener('input', (e) => {
    const val = parseInt(e.target.value, 10);
    elements.valBlur.textContent = `${val}px`;
    document.documentElement.style.setProperty('--overlay-blur', `${val}px`);
  });

  // 倒计时数字字号
  elements.inputFontScale.addEventListener('input', (e) => {
    const val = parseInt(e.target.value, 10);
    elements.valFontScale.textContent = `${val}px`;
    document.documentElement.style.setProperty('--timer-font-size', `${val}px`);
  });

  // 模拟熄屏 / 点亮按钮
  elements.btnToggleScreen.addEventListener('click', toggleScreenPower);

  // 重置计时按钮
  elements.btnResetTimer.addEventListener('click', resetScreenTimer);

  // 立即触发遮罩测试按钮
  elements.btnTriggerNow.addEventListener('click', () => {
    if (!appState.isScreenOn) {
      toggleScreenPower();
    }
    showEyeCareOverlay();
  });
}

// ==========================================================================
// 8. 应用启动初始化
// ==========================================================================
function initApp() {
  // 初始化 SVG 环形周长属性
  if (elements.ringProgress) {
    elements.ringProgress.style.strokeDasharray = SVG_CIRCUMFERENCE;
    elements.ringProgress.style.strokeDashoffset = 0;
  }

  // 绑定控件事件
  bindControlInputs();

  // 更新时钟
  updateClockTime();
  setInterval(updateClockTime, 1000 * 30);

  // 启动主屏幕计时器心跳 (1秒/次)
  setInterval(tickScreenTimer, 1000);

  // 初始化场景和媒体状态
  switchScene('video');

  // 初始化面板看板
  updateDashboard();
}

// 页面加载完成后启动
document.addEventListener('DOMContentLoaded', initApp);

