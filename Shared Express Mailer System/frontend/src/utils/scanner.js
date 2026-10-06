/**
 * 摄像头扫码工具 —— 袋码二维码/条码识别的公共底座
 * （用户端扫码取袋、站点端回收/出入库/消毒等页面复用）。
 *
 * 策略（设计书 10.2 移动端能力）：
 *  1) 优先使用浏览器原生 BarcodeDetector（Chrome/Edge 等，性能好、无需额外脚本）；
 *  2) 原生不可用时动态 import('jsqr') 降级为纯 JS 解码（兼容 iOS Safari 等）；
 *  3) navigator.mediaDevices 不可用时抛错，由页面兜底为手动输入袋码。
 *
 * 用法：
 *   const scanner = createScanner()
 *   await scanner.start(videoEl, (code) => { ... })   // 持续扫码，重复命中自带静默期
 *   scanner.stop()
 *   或一次性扫码：const code = await scanOnce({ videoEl })
 *
 * 注意：本文件只管「把画面里的码解出来」，不感知后端地址与业务语义。
 */

/** 摄像头不可用时的兜底文案（契约冻结，页面直接透传给用户） */
const UNSUPPORTED_MESSAGE = '当前浏览器不支持摄像头扫码，请手动输入袋码'

/** 命中一次后的静默期：避免同一只袋在连续帧里被反复上报 */
const RESULT_COOLDOWN_MS = 1000

/** jsQR 抽帧间隔：逐帧解码开销大，180ms 足够跟手又不过载 CPU */
const JSQR_INTERVAL_MS = 180

/** 送进 jsQR 的画面上限宽度：再大对二维码识别没有增益，只是浪费 CPU */
const JSQR_MAX_WIDTH = 640

function isCameraSupported() {
  return (
    typeof navigator !== 'undefined' &&
    !!navigator.mediaDevices &&
    typeof navigator.mediaDevices.getUserMedia === 'function'
  )
}

/** 把 getUserMedia 的错误翻译成用户能看懂的中文 */
function normalizeCameraError(error) {
  const name = error && error.name
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return new Error('未获得摄像头权限，请在浏览器中允许后重试，或手动输入袋码')
  }
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError' || name === 'OverconstrainedError') {
    return new Error('未检测到可用的摄像头，请手动输入袋码')
  }
  if (error instanceof Error && error.message) return error
  return new Error(UNSUPPORTED_MESSAGE)
}

class CameraScanner {
  constructor() {
    this.videoEl = null
    this.onResult = null
    this.stream = null
    this.running = false
    this.decodeKind = ''
    this.detector = null
    this.jsQR = null
    this.canvas = null
    this.canvasCtx = null
    this.rafId = null
    this.timerId = null
    this.lastResultAt = 0
  }

  /**
   * 启动摄像头并持续扫码。
   * @param {HTMLVideoElement} videoEl 承载实时画面的 video 元素
   * @param {(text: string) => void} onResult 命中回调（自带 1s 静默期）
   */
  async start(videoEl, onResult) {
    this.stop()
    if (!videoEl || typeof videoEl !== 'object') {
      throw new Error('缺少视频容器，无法启动扫码')
    }
    if (!isCameraSupported()) {
      throw new Error(UNSUPPORTED_MESSAGE)
    }

    this.videoEl = videoEl
    this.onResult = onResult

    let stream
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: { ideal: 'environment' }, // 优先后置摄像头
          width: { ideal: 1280 },
          height: { ideal: 720 },
        },
        audio: false,
      })
    } catch (error) {
      throw normalizeCameraError(error)
    }

    this.stream = stream
    videoEl.setAttribute('playsinline', 'true')
    videoEl.muted = true
    videoEl.srcObject = stream
    try {
      await videoEl.play()
    } catch (error) {
      this.stop()
      throw normalizeCameraError(error)
    }

    this.running = true
    await this.startDecoding()
  }

  /** 停止扫码并释放摄像头（可重复调用） */
  stop() {
    this.running = false
    this.decodeKind = ''
    if (this.rafId != null) {
      cancelAnimationFrame(this.rafId)
      this.rafId = null
    }
    if (this.timerId != null) {
      clearInterval(this.timerId)
      this.timerId = null
    }
    if (this.stream) {
      this.stream.getTracks().forEach((track) => track.stop())
      this.stream = null
    }
    if (this.videoEl) {
      this.videoEl.pause()
      this.videoEl.srcObject = null
    }
    this.detector = null
    this.jsQR = null
    this.canvas = null
    this.canvasCtx = null
    this.lastResultAt = 0
  }

  /* ---------- 解码策略 ---------- */

  async startDecoding() {
    if (typeof window !== 'undefined' && 'BarcodeDetector' in window) {
      try {
        const detector = await this.createDetector()
        if (detector) {
          this.detector = detector
          this.decodeKind = 'barcode-detector'
          this.detectLoop()
          return
        }
      } catch (error) {
        // 原生识别器不可用时静默降级到 jsQR，不打扰用户
      }
    }
    await this.startJsQrDecoding()
  }

  /** 构造原生识别器；二维码/条码都不受支持时返回 null，交给 jsQR */
  async createDetector() {
    const BarcodeDetector = window.BarcodeDetector
    if (typeof BarcodeDetector.getSupportedFormats === 'function') {
      const supported = await BarcodeDetector.getSupportedFormats()
      const formats = ['qr_code', 'code_128'].filter((format) => supported.includes(format))
      if (!formats.length) return null
      return new BarcodeDetector({ formats })
    }
    return new BarcodeDetector()
  }

  async startJsQrDecoding() {
    let jsQR = null
    try {
      const module = await import('jsqr')
      jsQR = (module && module.default) || module
    } catch (error) {
      jsQR = null
    }
    if (typeof jsQR !== 'function') {
      this.stop()
      throw new Error('扫码组件加载失败，请刷新重试或手动输入袋码')
    }
    if (!this.running) return

    this.jsQR = jsQR
    this.decodeKind = 'jsqr'
    this.canvas = document.createElement('canvas')
    this.canvasCtx = this.canvas.getContext('2d', { willReadFrequently: true })
    this.timerId = setInterval(() => this.decodeWithJsQr(), JSQR_INTERVAL_MS)
  }

  async detectLoop() {
    if (!this.running || this.decodeKind !== 'barcode-detector') return
    try {
      const results = await this.detector.detect(this.videoEl)
      if (results && results.length) this.report(results[0].rawValue)
    } catch (error) {
      // 单帧识别失败（画面未就绪等）不中断整体流程，下一帧继续
    }
    if (this.running && this.decodeKind === 'barcode-detector') {
      this.rafId = requestAnimationFrame(() => this.detectLoop())
    }
  }

  decodeWithJsQr() {
    if (!this.running || this.decodeKind !== 'jsqr' || !this.videoEl || !this.canvasCtx) return
    const width = this.videoEl.videoWidth
    const height = this.videoEl.videoHeight
    if (!width || !height) return

    // 等比缩到 640 宽以内再解码，控制移动端 CPU 占用
    const scale = Math.min(1, JSQR_MAX_WIDTH / width)
    const w = Math.max(1, Math.round(width * scale))
    const h = Math.max(1, Math.round(height * scale))
    this.canvas.width = w
    this.canvas.height = h
    this.canvasCtx.drawImage(this.videoEl, 0, 0, w, h)
    const imageData = this.canvasCtx.getImageData(0, 0, w, h)
    const code = this.jsQR(imageData.data, w, h, { inversionAttempts: 'dontInvert' })
    if (code && code.data) this.report(code.data)
  }

  report(text) {
    const value = typeof text === 'string' ? text.trim() : ''
    if (!value) return
    const now = Date.now()
    if (now - this.lastResultAt < RESULT_COOLDOWN_MS) return
    this.lastResultAt = now
    if (typeof this.onResult === 'function') this.onResult(value)
  }
}

/**
 * 创建扫码器实例。
 * @returns {{ start: (videoEl: HTMLVideoElement, onResult: (text: string) => void) => Promise<void>, stop: () => void }}
 */
export function createScanner() {
  return new CameraScanner()
}

/**
 * 「跑一次」：用给定扫码器开扫，解出第一个码后立即停止并返回文本。
 * videoEl 省略时内部创建一个隐藏的 video 元素做取帧；timeout 默认 60s。
 * @returns {Promise<string>}
 */
function runOnce(scanner, options = {}) {
  const { videoEl = null, timeout = 60000 } = options

  return new Promise((resolve, reject) => {
    let settled = false
    let hiddenVideo = null
    let target = videoEl
    if (!target) {
      hiddenVideo = document.createElement('video')
      hiddenVideo.setAttribute('playsinline', 'true')
      hiddenVideo.setAttribute('aria-hidden', 'true')
      hiddenVideo.muted = true
      hiddenVideo.style.position = 'fixed'
      hiddenVideo.style.width = '1px'
      hiddenVideo.style.height = '1px'
      hiddenVideo.style.opacity = '0'
      hiddenVideo.style.pointerEvents = 'none'
      document.body.appendChild(hiddenVideo)
      target = hiddenVideo
    }

    const timer = setTimeout(() => finish(new Error('扫码超时，请重试或手动输入袋码')), timeout)

    function finish(error, value) {
      if (settled) return
      settled = true
      clearTimeout(timer)
      scanner.stop()
      if (hiddenVideo) hiddenVideo.remove()
      if (error) reject(error)
      else resolve(value)
    }

    try {
      scanner.start(target, (text) => finish(null, text)).catch((error) => finish(error))
    } catch (error) {
      finish(error)
    }
  })
}

/**
 * 一次性扫码：启动摄像头，解出第一个码后立即停止并返回文本。
 * @param {{ videoEl?: HTMLVideoElement, timeout?: number }} options
 * @returns {Promise<string>}
 */
export async function scanOnce(options = {}) {
  return runOnce(createScanner(), options)
}

/**
 * 兼容契约文档中的命名，两种调用形态都支持：
 *  - 回调式 scanFromCamera(videoEl, onResult)：返回 { stop(), promise } 控制器，
 *    命中后回调 onResult（不再 resolve 给回调方重复消费）；
 *  - 承诺式 scanFromCamera(videoEl) / scanFromCamera({ videoEl, timeout })：返回 Promise<string>。
 */
export function scanFromCamera(videoElOrOptions, onResult) {
  const isVideoElement =
    !!videoElOrOptions &&
    typeof videoElOrOptions === 'object' &&
    typeof videoElOrOptions.play === 'function'
  const options = isVideoElement ? { videoEl: videoElOrOptions } : videoElOrOptions || {}
  const scanner = createScanner()

  if (typeof onResult === 'function') {
    const promise = runOnce(scanner, options)
    // 回调式调用：结果以回调交付，promise 仅用于感知失败/超时
    promise.then((code) => onResult(code)).catch(() => {})
    return {
      promise,
      stop: () => scanner.stop(),
    }
  }
  return runOnce(scanner, options)
}
