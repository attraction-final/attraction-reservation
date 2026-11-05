// 웹(PC)에서 봐도 앱처럼 보이게, 화면을 휴대폰 모양 틀 안에 넣어 줍니다.
// 실제 휴대폰(폭 480px 이하)에서는 styles.css 의 @media 규칙 때문에 틀 없이 꽉 차게 보여요.
export default function DeviceFrame({ children }) {
  return (
    <div className="device-stage">
      <div className="device">{children}</div>
    </div>
  );
}
