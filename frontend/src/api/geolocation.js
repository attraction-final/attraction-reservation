// 브라우저에서 지금 위치(위도·경도)를 한 번 받아 오는 함수예요.
// 루트 추천을 "현재 위치 출발"로 받을 때 써요. 받은 좌표는 요청에만 쓰고 어디에도 저장하지 않아요.
//
// 사용 예)
//   const position = await getCurrentLocation();
//   // { latitude, longitude, accuracy, altitude, altitudeAccuracy, heading, speed, timestamp }
//
// - 서버(POST /api/route-recommendations)가 받는 값 그대로예요. accuracy(오차 반경 m)는 서버가 필수로 받아요.
// - altitude 등 기기에 따라 없는 값은 null 이에요.
// - 실패하면 Error 를 던지고, error.message 에 사용자에게 보여 줄 안내 문구가 들어 있어요.
// - HTTPS 또는 localhost 에서만 동작해요. (그 밖에서는 브라우저가 위치를 주지 않아요)

const OPTIONS = {
  enableHighAccuracy: true, // 공원 안 어트랙션 사이 거리는 수백 m라서 정확한 위치가 필요해요
  timeout: 10000,           // 10초 안에 못 받으면 포기해요
  maximumAge: 60000,        // 1분 안에 받은 위치는 다시 써요 (걸어서 약 50m 차이)
};

// GeolocationPositionError.code → 안내 문구
const MESSAGES = {
  1: '위치 권한이 거부되어 현재 위치를 알 수 없어요. 브라우저 설정에서 이 사이트의 위치 권한을 허용한 뒤 다시 시도해 주세요.',
  2: '현재 위치를 확인할 수 없어요. 실내라면 밖으로 나가 잠시 후 다시 시도해 주세요.',
  3: '현재 위치를 받는 데 시간이 너무 오래 걸려요. 잠시 후 다시 시도해 주세요.',
};

export function getCurrentLocation() {
  return new Promise((resolve, reject) => {
    if (!('geolocation' in navigator)) {
      reject(new Error('이 브라우저는 현재 위치 기능을 지원하지 않아요. 다른 브라우저에서 시도해 주세요.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      ({ coords, timestamp }) => resolve({
        latitude: coords.latitude,
        longitude: coords.longitude,
        accuracy: coords.accuracy,
        altitude: coords.altitude,
        altitudeAccuracy: coords.altitudeAccuracy,
        // 멈춰 있으면 브라우저가 NaN 을 줘요. JSON 에서는 null 이 되지만 명시적으로 바꿔 둬요.
        heading: Number.isNaN(coords.heading) ? null : coords.heading,
        speed: coords.speed,
        timestamp: Math.round(timestamp), // 측정 시각(ms). 서버는 오래된 위치면 경고를 붙여요
      }),
      (error) => reject(new Error(MESSAGES[error.code] ?? MESSAGES[2])),
      OPTIONS,
    );
  });
}
