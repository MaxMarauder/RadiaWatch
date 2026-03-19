import { createWidget, widget, prop, align } from '@zos/ui'
import { Vibrator, VIBRATOR_SCENE_SHORT_STRONG, VIBRATOR_SCENE_SHORT_MIDDLE } from '@zos/sensor'
import { BasePage } from '@zeppos/zml/base-page'

const GREEN  = 0x00e676
const YELLOW = 0xffff00
const RED    = 0xff0000

const vibrator = new Vibrator()

Page(
    BasePage({
        onInit() {
            this._alarm1 = 0
            this._alarm2 = 0
            this._aboveAlarm1 = false
            this._aboveAlarm2 = false

            setInterval(() => {
                this.httpRequest({ url: 'http://127.0.0.1:8080/radiation' })
                    .then((res) => {
                        const data = res.body

                        if (!data.connected) {
                            this._showNoData()
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
                    })
                    .catch(() => { this._showNoData() })
            }, 500)
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
                h: 480,
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
        },
    })
)
