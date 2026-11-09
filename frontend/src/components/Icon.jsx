// 앱에서 쓰는 선(stroke) 아이콘 모음입니다.
// 사용법: <Icon name="home" size={22} />   색은 글자색(currentColor)을 따라가요.
const PATHS = {
  home: <path d="M3.5 10.5 12 3.5l8.5 7V20a1 1 0 0 1-1 1H15v-6H9v6H4.5a1 1 0 0 1-1-1z" />,
  ferris: <><circle cx="12" cy="10" r="6.5" /><path d="M12 3.5v13M5.5 10h13M8 21l4-4.5 4 4.5" /></>,
  ferrisFull: <><circle cx="12" cy="10" r="6.5" /><path d="M12 3.5v13M5.5 10h13M7.4 5.4l9.2 9.2M16.6 5.4l-9.2 9.2M8 21l4-4.5 4 4.5" /></>,
  calendar: <><rect x="3.5" y="5" width="17" height="15.5" rx="3" /><path d="M3.5 10h17M8 3v4M16 3v4" /></>,
  calendarCheck: <><rect x="3.5" y="5" width="17" height="15.5" rx="3" /><path d="M3.5 10h17M8 3v4M16 3v4M9 15l2 2 4-4" /></>,
  ticket: <path d="M4 6h16a1 1 0 0 1 1 1v3a2 2 0 0 0 0 4v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-3a2 2 0 0 0 0-4V7a1 1 0 0 1 1-1z" />,
  ticketPlus: <><path d="M4 6h16a1 1 0 0 1 1 1v3a2 2 0 0 0 0 4v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-3a2 2 0 0 0 0-4V7a1 1 0 0 1 1-1z" /><path d="M12 9v6M9 12h6" /></>,
  user: <><circle cx="12" cy="8" r="4" /><path d="M4.5 21c1.4-3.8 4.3-5.8 7.5-5.8s6.1 2 7.5 5.8" /></>,
  users: <><circle cx="9" cy="8" r="3.5" /><path d="M2.5 20c1-3.4 3.6-5.2 6.5-5.2s5.5 1.8 6.5 5.2M16 4.8a3.3 3.3 0 0 1 0 6.4M18 14.8c1.8.6 3 2.4 3.5 5.2" /></>,
  receipt: <><path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" /><path d="M9 8h6M9 12h6" /></>,
  map: <><path d="M9 4 3 6v14l6-2 6 2 6-2V4l-6 2z" /><path d="M9 4v14M15 6v14" /></>,
  back: <path d="M15 5l-7 7 7 7" />,
  chevron: <path d="M9 5l7 7-7 7" />,
  search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" /></>,
  filter: <path d="M4 6h16M7 12h10M10 18h4" />,
  clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  info: <><circle cx="12" cy="12" r="9" /><path d="M12 11v5.5M12 7.5v.01" /></>,
  alert: <><circle cx="12" cy="12" r="9" /><path d="M12 7.5v5.5M12 16.5v.01" /></>,
  check: <path d="M5 12l5 5 9-10" />,
  trash: <path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13" />,
  edit: <path d="M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4" />,
  // 말풍선 (어트랙션 질문 버튼)
  chat: <><path d="M4 5.5h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-9l-4.5 3.5V17.5H4a1 1 0 0 1-1-1v-10a1 1 0 0 1 1-1z" /><path d="M8 10.5h8M8 13.5h5" /></>,
  walk: <><circle cx="13" cy="4" r="2" /><path d="M9 21l2-6 3 3v3M7 12l3-4 4 1 2 4" /></>,
};

export default function Icon({ name, size = 22, strokeWidth = 1.9, color = 'currentColor', style }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={style}>
      {PATHS[name]}
    </svg>
  );
}

// 노란 별 (로고 옆에 쓰는 채워진 아이콘)
export function StarIcon({ size = 16, color = '#1F2A44' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={color} aria-hidden="true">
      <path d="M12 2.5l2.9 6 6.6.8-4.9 4.5 1.3 6.5L12 17l-5.9 3.3 1.3-6.5-4.9-4.5 6.6-.8z" />
    </svg>
  );
}
