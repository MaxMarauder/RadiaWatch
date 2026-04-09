import { createWidget, widget, prop, align, event } from '@zos/ui'
import { Vibrator, VIBRATOR_SCENE_SHORT_STRONG, VIBRATOR_SCENE_SHORT_MIDDLE } from '@zos/sensor'
import { setPageBrightTime, resetPageBrightTime, pauseDropWristScreenOff, resetDropWristScreenOff } from '@zos/display'
import { localStorage } from '@zos/storage'
import { BasePage } from '@zeppos/zml/base-page'

const GREEN  = 0x00e676
const YELLOW = 0xffff00
const RED    = 0xff0000

const vibrator = new Vibrator()

Page(
    BasePage({
        onInit() {
            this._keepLit = localStorage.getItem('keepScreenLit') === 'true'
            this._applyScreenSetting()

            this._alarm1 = 0
            this._alarm2 = 0
            this._aboveAlarm1 = false
            this._aboveAlarm2 = false

            const poll = () => {
                // Use an explicit cancellable timeout instead of Promise.race so we
                // don't allocate a new Promise + Error object on every 500ms tick.
                let settled = false
                const timeoutId = setTimeout(() => {
                    settled = true
                    this._showNoData()
                    this._pollTimer = setTimeout(poll, 500)
                }, 3000)

                // Call messaging.request() directly instead of httpRequest() to
                // bypass the hmApp.onMessage/offMessage roundtrip that the framework
                // wraps httpRequest with. That layer registers 2 native message handlers
                // per request plus an extra Promise and only cleans them up in .finally()
                // after the BLE response returns — under 500ms polling this accumulates.
                // Pass an explicit timeout slightly longer than our app-level one so
                // abandoned BLE requests (after app timeout fires) are cleaned up promptly
                // rather than lingering for the framework's default 60 seconds.
                this.messaging.request(
                    { method: 'http.request', params: { url: 'http://127.0.0.1:8080/radiation' } },
                    { timeout: 4000 }
                )
                    .then((res) => {
                        if (settled) return
                        settled = true
                        clearTimeout(timeoutId)

                        const data = res.body

                        if (!data.connected) {
                            this._showNoData()
                            this._pollTimer = setTimeout(poll, 500)
                            return
                        }

                        const usvh = data.usvh

                        // Cache alarm thresholds (non-zero values only)
                        if (data.alarm1 > 0) this._alarm1 = data.alarm1
                        if (data.alarm2 > 0) this._alarm2 = data.alarm2

                        // Determine alarm state
                        const nowAboveAlarm2 = this._alarm2 > 0 && usvh >= this._alarm2
                        const nowAboveAlarm1 = this._alarm1 > 0 && usvh >= this._alarm1

                        // Update display — done before vibration so a vibration error
                        // can never prevent the display from updating
                        const color = nowAboveAlarm2 ? RED : nowAboveAlarm1 ? YELLOW : GREEN
                        this._waiting.setProperty(prop.VISIBLE, false)
                        this._value.setProperty(prop.MORE, { color, text: usvh.toFixed(2) })
                        this._value.setProperty(prop.VISIBLE, true)
                        this._units.setProperty(prop.MORE, { color })
                        this._units.setProperty(prop.VISIBLE, true)

                        // Update state before vibration for the same reason
                        const wasAboveAlarm1 = this._aboveAlarm1
                        const wasAboveAlarm2 = this._aboveAlarm2
                        this._aboveAlarm1 = nowAboveAlarm1
                        this._aboveAlarm2 = nowAboveAlarm2

                        // Vibrate on upward threshold crossings (isolated so any error
                        // doesn't affect the display update above)
                        try {
                            if (nowAboveAlarm2 && !wasAboveAlarm2) {
                                vibrator.start({ mode: VIBRATOR_SCENE_SHORT_STRONG })
                            } else if (nowAboveAlarm1 && !wasAboveAlarm1) {
                                vibrator.start({ mode: VIBRATOR_SCENE_SHORT_MIDDLE })
                            }
                        } catch (_) {}
                        this._pollTimer = setTimeout(poll, 500)
                    })
                    .catch(() => {
                        if (settled) return
                        settled = true
                        clearTimeout(timeoutId)
                        this._showNoData()
                        this._pollTimer = setTimeout(poll, 500)
                    })
            }
            this._pollTimer = setTimeout(poll, 0)
        },

        _applyScreenSetting() {
            if (this._keepLit) {
                setPageBrightTime({ brightTime: 2147483000 })
                pauseDropWristScreenOff({ duration: 0 })
            } else {
                resetPageBrightTime()
                resetDropWristScreenOff()
            }
        },

        _toggleScreenLit() {
            this._keepLit = !this._keepLit
            localStorage.setItem('keepScreenLit', String(this._keepLit))
            this._applyScreenSetting()
            this._bulb.setProperty(prop.MORE, {
                text: this._keepLit ? '\u25cf' : '\u25cb',
            })
        },

        _showNoData() {
            this._value.setProperty(prop.VISIBLE, false)
            this._units.setProperty(prop.VISIBLE, false)
            this._waiting.setProperty(prop.TEXT, 'No data')
            this._waiting.setProperty(prop.VISIBLE, true)
        },

        build() {
            this._waiting = createWidget(widget.TEXT, {
                x: 0,
                y: 0,
                w: 480,
                h: 415,
                text: 'Waiting...',
                text_size: 36,
                color: YELLOW,
                align_h: align.CENTER_H,
                align_v: align.CENTER_V,
            })

            this._symbol = createWidget(widget.TEXT, {
                x: 0,
                y: 90,
                w: 480,
                h: 90,
                text: '\u2622',
                text_size: 72,
                color: YELLOW,
                align_h: align.CENTER_H,
                align_v: align.CENTER_V,
            })

            this._value = createWidget(widget.TEXT, {
                x: 0,
                y: 185,
                w: 480,
                h: 130,
                text: '',
                text_size: 100,
                color: YELLOW,
                align_h: align.CENTER_H,
                align_v: align.CENTER_V,
                visible: false,
            })

            this._units = createWidget(widget.TEXT, {
                x: 0,
                y: 320,
                w: 480,
                h: 60,
                text: '\u00b5Sv/h',
                text_size: 40,
                color: YELLOW,
                align_h: align.CENTER_H,
                align_v: align.CENTER_V,
                visible: false,
            })

            // Explicitly hide in case createWidget visible:false is not reliable
            this._value.setProperty(prop.VISIBLE, false)
            this._units.setProperty(prop.VISIBLE, false)

            // Light bulb toggle — created last so it layers above the waiting overlay
            this._bulb = createWidget(widget.TEXT, {
                x: 0,
                y: 415,
                w: 480,
                h: 55,
                text: this._keepLit ? '\u25cf' : '\u25cb',
                text_size: 32,
                color: YELLOW,
                align_h: align.CENTER_H,
                align_v: align.CENTER_V,
            })
            this._bulb.addEventListener(event.CLICK_DOWN, () => this._toggleScreenLit())
        },

        onDestroy() {
            clearTimeout(this._pollTimer)
            resetDropWristScreenOff()
        },
    })
)
