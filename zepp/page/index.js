import { createWidget, widget, prop, align } from '@zos/ui'
import { BasePage } from '@zeppos/zml/base-page'

const YELLOW = 0xffff00

Page(
    BasePage({
        onInit() {
            setInterval(() => {
                this.httpRequest({ url: 'http://127.0.0.1:8080/radiation' })
                    .then((res) => {
                        const data = res.body
                        this._waiting.setProperty(prop.VISIBLE, false)
                        this._symbol.setProperty(prop.VISIBLE, true)
                        this._value.setProperty(prop.VISIBLE, true)
                        this._value.setProperty(prop.TEXT, data.usvh.toFixed(2))
                        this._units.setProperty(prop.VISIBLE, true)
                    })
                    .catch(() => {})
            }, 1000)
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
                visible: false,
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
        },
    })
)
