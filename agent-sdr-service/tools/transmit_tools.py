from __future__ import annotations

from typing import Literal

from pydantic import Field, field_validator, model_validator

from hardware.sdr_controller import (
    DEFAULT_CENTER_FREQ_HZ,
    DEFAULT_TEXT_PACKET_REPETITIONS,
    DEFAULT_TONE_FREQ_HZ,
    DEFAULT_TONE_SAMPLE_RATE_HZ,
    SDRController,
    normalize_text_modulation_scheme,
)
from tools import ToolInput, ToolSpec
from tools.policy import (
    FSK_DEVIATION_MAX_HZ,
    FSK_DEVIATION_MIN_HZ,
    MAX_SAMPLES_PER_SYMBOL,
    RF_CENTER_MAX_HZ,
    RF_CENTER_MIN_HZ,
    RX_GAIN_MAX_DB,
    RX_GAIN_MIN_DB,
    SAMPLE_RATE_MAX_HZ,
    SAMPLE_RATE_MIN_HZ,
    SEARCH_SPAN_MAX_HZ,
    SEARCH_SPAN_MIN_HZ,
    SIGNAL_AMPLITUDE_MAX,
    SIGNAL_AMPLITUDE_MIN,
    SYMBOL_RATE_MAX_BAUD,
    SYMBOL_RATE_MIN_BAUD,
    TX_GAIN_MAX_DB,
    TX_GAIN_MIN_DB,
)


ModulationScheme = Literal["2-FSK", "BPSK", "QPSK", "16-QAM"]


class FskSendRequest(ToolInput):
    text: str = Field(min_length=1, max_length=256, description="必须从用户指令中提取出的要发送文本内容。")
    modulation_scheme: ModulationScheme = Field(
        default="2-FSK",
        description="固定文本收发调制方式。用户未指定时使用 2-FSK；明确指定时可用 BPSK、QPSK 或 16-QAM。",
    )
    center_freq_hz: float = Field(default=DEFAULT_CENTER_FREQ_HZ, ge=RF_CENTER_MIN_HZ, le=RF_CENTER_MAX_HZ, description="中心频率，单位 Hz；当前允许 1.2-6.0 GHz。")
    samp_rate: float = Field(default=1e6, ge=SAMPLE_RATE_MIN_HZ, le=SAMPLE_RATE_MAX_HZ, description="采样率，单位 Hz；允许 100 kHz-40 MHz。")
    sym_rate: float = Field(default=10e3, ge=SYMBOL_RATE_MIN_BAUD, le=SYMBOL_RATE_MAX_BAUD, description="符号率，单位 Baud；允许 1 k-2 MBaud。")
    f_dev: float = Field(default=25e3, ge=FSK_DEVIATION_MIN_HZ, le=FSK_DEVIATION_MAX_HZ, description="2-FSK 频偏，单位 Hz；允许 1 kHz-5 MHz。")
    tx_gain: float = Field(default=20.0, ge=TX_GAIN_MIN_DB, le=TX_GAIN_MAX_DB, description="发射增益，单位 dB；当前允许 0-31.5 dB。")
    rx_gain: float = Field(default=20.0, ge=RX_GAIN_MIN_DB, le=RX_GAIN_MAX_DB, description="接收增益，单位 dB；当前允许 0-37.5 dB。")
    amp: float = Field(default=0.6, ge=SIGNAL_AMPLITUDE_MIN, le=SIGNAL_AMPLITUDE_MAX, description="归一化发送信号幅度，允许 0.01-1.0。")
    packet_repetitions: int = Field(
        default=DEFAULT_TEXT_PACKET_REPETITIONS,
        ge=1,
        le=8,
        description="同一个文本包重复发送次数，用于提高短包解码成功率。",
    )

    @field_validator("modulation_scheme", mode="before")
    @classmethod
    def normalize_modulation(cls, value: str) -> str:
        return normalize_text_modulation_scheme(value)

    @model_validator(mode="after")
    def validate_baseband_rates(self) -> "FskSendRequest":
        if self.sym_rate > self.samp_rate / 2:
            raise ValueError("sym_rate 必须不超过 samp_rate/2")
        if self.samp_rate / self.sym_rate > MAX_SAMPLES_PER_SYMBOL:
            raise ValueError(
                f"samp_rate/sym_rate 不能超过 {MAX_SAMPLES_PER_SYMBOL}，避免生成过大的发送数组"
            )
        if self.modulation_scheme == "2-FSK" and self.f_dev + self.sym_rate > self.samp_rate / 2:
            raise ValueError("2-FSK 的 f_dev + sym_rate 必须不超过 samp_rate/2")
        return self


class AdaptiveTransmitRequest(ToolInput):
    text: str = Field(min_length=1, max_length=256, description="正式发送的文本内容，必须从用户指令中提取。")
    center_freq_hz: float = Field(default=DEFAULT_CENTER_FREQ_HZ, ge=RF_CENTER_MIN_HZ, le=RF_CENTER_MAX_HZ, description="中心频率，单位 Hz；当前允许 1.2-6.0 GHz。")
    probe_text: str = Field(default="channel_probe", min_length=1, max_length=64, description="用于 BPSK 探测并解调校验的链路探测文本。")


class AutoOptimalTransmitRequest(ToolInput):
    text: str = Field(default="", max_length=256, description="若需要发送文本，填写文本内容；否则留空。")
    modulation_scheme: ModulationScheme = Field(default="2-FSK", description="非自适应文本发送时使用的固定调制方式，默认 2-FSK。")
    center_freq_hz: float = Field(default=DEFAULT_CENTER_FREQ_HZ, ge=RF_CENTER_MIN_HZ, le=RF_CENTER_MAX_HZ, description="目标工作频率，单位 Hz；当前允许 1.2-6.0 GHz。")
    search_span_hz: float = Field(default=30e6, ge=SEARCH_SPAN_MIN_HZ, le=SEARCH_SPAN_MAX_HZ, description="频点搜索跨度，单位 Hz；允许 5-100 MHz。")
    enable_tone: bool = Field(default=False, description="是否在最优频点启动 Tone/正弦波可视化。")
    tone_freq_hz: float = Field(default=DEFAULT_TONE_FREQ_HZ, gt=0, lt=DEFAULT_TONE_SAMPLE_RATE_HZ / 2, description="Tone 基带频率，单位 Hz；当前固定 1 MHz 采样率下必须小于 500 kHz。")
    use_adaptive_modulation: bool = Field(
        default=False,
        description="是否按 SNR 自动切换 BPSK / QPSK / 16-QAM。",
    )

    @field_validator("modulation_scheme", mode="before")
    @classmethod
    def normalize_modulation(cls, value: str) -> str:
        return normalize_text_modulation_scheme(value)

    @model_validator(mode="after")
    def validate_search_window(self) -> "AutoOptimalTransmitRequest":
        half_span = self.search_span_hz / 2
        if self.center_freq_hz - half_span < RF_CENTER_MIN_HZ:
            raise ValueError("搜索频段下边界超出当前 USRP 支持范围")
        if self.center_freq_hz + half_span > RF_CENTER_MAX_HZ:
            raise ValueError("搜索频段上边界超出当前 USRP 支持范围")
        return self


def build_transmit_tools(controller: SDRController) -> list[ToolSpec]:
    return [
        ToolSpec(
            name="text_fsk_send_and_receive",
            description=(
                "执行固定调制的普通文本无线收发，必须把用户要发送的内容填入 text。"
                "用户未指定调制方式时默认使用 2-FSK；用户明确说用 BPSK、QPSK 或 16-QAM 发送文本时，也必须使用本工具并设置 modulation_scheme。"
                "如果用户说根据信道质量、链路质量、SNR、自适应、自动选择或调整调制方式，不要使用本工具。"
            ),
            schema_model=FskSendRequest,
            handler=lambda payload: controller.text_fsk_send_and_receive(**payload.model_dump()),
            category="hardware",
        ),
        ToolSpec(
            name="adaptive_modulation_transmit",
            description=(
                "先探测链路质量/SNR，再自动选择 BPSK、QPSK 或 16-QAM 完成文本发送、接收和对应调制方式解调。"
                "用户说根据信道质量、链路质量、SNR、自适应、自动选择调制方式或调整调制方式时必须使用本工具。"
            ),
            schema_model=AdaptiveTransmitRequest,
            handler=lambda payload: controller.adaptive_modulation_transmit(**payload.model_dump()),
            category="hardware",
        ),
        ToolSpec(
            name="auto_optimal_transmit",
            description=(
                "认知选频工具：先搜索更干净的频点，再执行文本发送或 Tone/正弦波可视化；"
                "非自适应文本发送默认使用 2-FSK，用户指定 BPSK、QPSK 或 16-QAM 时设置 modulation_scheme；"
                "用户要求找干净信道并显示可视化框图/波形/频谱时设置 enable_tone=true。"
            ),
            schema_model=AutoOptimalTransmitRequest,
            handler=lambda payload: controller.auto_optimal_transmit(**payload.model_dump()),
            category="hardware",
        ),
    ]
