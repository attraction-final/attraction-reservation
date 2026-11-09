-- k6가 검증할 기존 회원 200명의 조건별 기대 결과를 JSON Lines 400행으로 추출한다.
-- smoke는 처음 두 회원, warm-up/load는 TEST_VUS에 맞춰 처음 10명/20명/50명/100명/150명을 사용한다.
-- JSON_ARRAYAGG의 순서를 가정하지 않고 ORDER BY가 명시된 GROUP_CONCAT으로 상위 10건을 만든다.
SET SESSION group_concat_max_len = 65536;
WITH variants AS (
    SELECT 'all' AS variant UNION ALL SELECT 'reserved'
), ranked AS (
    SELECT u.id AS user_id,u.email,v.variant,r.id,r.reservation_status,
           ROW_NUMBER() OVER (PARTITION BY u.id,v.variant ORDER BY r.reservation_status DESC,r.id DESC) AS rn,
           COUNT(*) OVER (PARTITION BY u.id,v.variant) AS total,
           JSON_OBJECT('reservationId',r.id,'slotId',s.id,'ticketId',r.ticket_id,
                       'status',r.reservation_status,'reservationNumber',r.reservation_number,
                       'attractionName',a.name) AS expected_row
    FROM users u JOIN reservations r ON r.user_id = u.id
    JOIN attraction_slots s ON s.id = r.attraction_slot_id
    JOIN attractions a ON a.id = s.attraction_id
    CROSS JOIN variants v
    WHERE u.email REGEXP '^kan134-0[01][0-9][0-9]@example[.]test$'
      AND (v.variant = 'all' OR r.reservation_status = 'RESERVED')
)
SELECT JSON_OBJECT('userId',user_id,'email',email,'variant',variant,'total',MAX(total),
    'rows',JSON_EXTRACT(CONCAT('[',GROUP_CONCAT(expected_row ORDER BY rn SEPARATOR ','),']'),'$'))
FROM ranked WHERE rn <= 10 GROUP BY user_id,email,variant ORDER BY email,variant;
