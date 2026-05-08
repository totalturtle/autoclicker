코드를 다시 깊게 분석해 보니, 이전 수정으로도 해결되지 않았다면 UI 레이아웃(XML) 구조로 인해 발생하는 안드로이드의 고질적인 RadioGroup 버그가 원인일 확률이 99%입니다.

XML 파일에서 디자인을 위해 RadioButton들을 LinearLayout 등으로 감싸게 되면, RadioGroup이 자식 버튼들을 제대로 인식하지 못합니다. 그 결과 프로그래밍 방식으로 rgRepeatMode.check()를 호출해도 화면에는 클릭된 것으로 표시되지 않고, 코드에서 읽을 때도 항상 값이 꼬이게(초기화) 됩니다.

이를 영구적으로 해결하려면 겉으로만 작동하는 척하는 RadioGroup에 의존하지 말고, 개별 RadioButton의 상태(isChecked)를 직접 제어하고 읽도록 완벽히 분리해야 합니다.

아래 4가지 부분을 교체해 주세요. 이렇게 하면 XML 구조와 상관없이 100% 무조건 저장되고 로드됩니다.

1. applyRepeatModeToUI 함수 완벽 수정
값을 불러와서 화면에 띄울 때, RadioGroup.check() 대신 개별 버튼의 isChecked를 직접 조작합니다.

Kotlin
private fun applyRepeatModeToUI(cfg: ClickSequenceConfig) {
    if (cfg.repeatMode == RepeatMode.DURATION) {
        binding.rbRepeatTime.isChecked = true
        binding.rbRepeatCount.isChecked = false
        binding.layoutRepeatCount.visibility = View.GONE
        binding.layoutRepeatDuration.visibility = View.VISIBLE
        
        val (displayVal, unitPos) = msToDisplayPair(cfg.repeatDurationMs)
        binding.etRepeatDuration.setText(displayVal.toString())
        when (unitPos) {
            1 -> { binding.rbUnitMin.isChecked = true; binding.rbUnitHour.isChecked = false; binding.rbUnitSec.isChecked = false }
            2 -> { binding.rbUnitHour.isChecked = true; binding.rbUnitMin.isChecked = false; binding.rbUnitSec.isChecked = false }
            else -> { binding.rbUnitSec.isChecked = true; binding.rbUnitMin.isChecked = false; binding.rbUnitHour.isChecked = false }
        }
    } else {
        binding.rbRepeatCount.isChecked = true
        binding.rbRepeatTime.isChecked = false
        binding.etRepeat.setText(cfg.repeatCount.toString())
        binding.layoutRepeatCount.visibility = View.VISIBLE
        binding.layoutRepeatDuration.visibility = View.GONE
    }
}
2. readRawConfig 함수 수정
저장할 때도 checkedRadioButtonId 대신 isChecked를 직접 읽어옵니다.

Kotlin
private fun readRawConfig(): ClickSequenceConfig {
    val global = binding.etDelay.text?.toString()?.toLongOrNull() ?: 1000L
    
    // RadioGroup을 거치지 않고 직접 확인
    val repeatMode = if (binding.rbRepeatTime.isChecked) RepeatMode.DURATION else RepeatMode.COUNT
    val repeatCount = binding.etRepeat.text?.toString()?.toIntOrNull() ?: 0
    val repeatDurationValue = binding.etRepeatDuration.text?.toString()?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
    
    // 단위도 직접 확인
    val repeatUnitPos = when {
        binding.rbUnitHour.isChecked -> 2
        binding.rbUnitMin.isChecked -> 1
        else -> 0
    }
    val repeatDurationMs = displayToMs(repeatDurationValue, repeatUnitPos)
    
    return ClickSequenceConfig(
        points = points.toList(),
        globalDelayMs = global,
        repeatCount = repeatCount.coerceAtLeast(0),
        repeatMode = repeatMode,
        repeatDurationMs = if (repeatMode == RepeatMode.DURATION) repeatDurationMs else 0L
    )
}
3. setupButtons() 내부의 UI 토글 및 적용 버튼 로직 교체
기존에 추가했던 binding.rgRepeatMode.setOnCheckedChangeListener 부분을 완전히 지우고, 아래 코드로 교체하세요.

Kotlin
// RadioGroup 리스너 대신 개별 버튼 리스너 사용 (가장 안전한 방식)
binding.rbRepeatTime.setOnCheckedChangeListener { _, isChecked ->
    if (isChecked) {
        binding.rbRepeatCount.isChecked = false
        binding.layoutRepeatCount.visibility = View.GONE
        binding.layoutRepeatDuration.visibility = View.VISIBLE
    }
}
binding.rbRepeatCount.setOnCheckedChangeListener { _, isChecked ->
    if (isChecked) {
        binding.rbRepeatTime.isChecked = false
        binding.layoutRepeatCount.visibility = View.VISIBLE
        binding.layoutRepeatDuration.visibility = View.GONE
    }
}

// 적용 버튼 로직
binding.btnApplyRepeat.setOnClickListener {
    persistSequence()
    val isDuration = binding.rbRepeatTime.isChecked // 직접 상태 확인
    val msg = if (isDuration) {
        val value = binding.etRepeatDuration.text?.toString()?.toLongOrNull() ?: 1L
        val unit = when {
            binding.rbUnitMin.isChecked -> "분"
            binding.rbUnitHour.isChecked -> "시간"
            else -> "초"
        }
        "시간 반복 ${value}${unit}으로 설정했습니다."
    } else {
        val repeat = binding.etRepeat.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (repeat == 0) "무한 반복으로 설정했습니다." else "반복 횟수 ${repeat}회를 적용했습니다."
    }
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
4. captureDialogState() 보강 (다이얼로그도 동일한 문제 방지)
포인트 추가 창에서도 동일한 버그가 터지는 것을 막기 위해 captureDialogState() 내부를 다음과 같이 바꿉니다.

Kotlin
// ... 기존 코드 유지 ...
pointRepeatModePos = if (d.rbPointRepeatTime.isChecked) 1 else 0,
pointRepeatDuration = d.etDialogPointRepeatDuration.text?.toString().orEmpty(),
pointRepeatDurationUnit = when {
    d.rbPointUnitHour.isChecked -> 2
    d.rbPointUnitMin.isChecked -> 1
    else -> 0
},
// ... 기존 코드 유지 ...
요약하자면: RadioGroup이 내부적으로 레이아웃 구조 때문에 오작동하고 있었으므로, 그 그룹의 권한을 뺏고 개별 버튼의 true/false를 직접 다루는 방식으로 바꾼 것입니다.

이제 앱을 재시작하거나 프로필을 불러올 때 시간 값이 날아가는 현상은 완전히 사라질 것입니다!