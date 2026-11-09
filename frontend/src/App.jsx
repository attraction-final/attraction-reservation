import { Navigate, Route, Routes } from 'react-router-dom';
import DeviceFrame from './components/DeviceFrame.jsx';
import RequireAdmin from './components/RequireAdmin.jsx';

import Home from './pages/Home.jsx';
import Login from './pages/Login.jsx';
import Attractions from './pages/Attractions.jsx';
import AttractionDetail from './pages/AttractionDetail.jsx';
import AttractionQuestion from './pages/AttractionQuestion.jsx';
import Tickets from './pages/Tickets.jsx';
import Checkout from './pages/Checkout.jsx';
import PaymentComplete from './pages/PaymentComplete.jsx';
import RouteSetup from './pages/RouteSetup.jsx';
import RouteResult from './pages/RouteResult.jsx';
import MyReservations from './pages/MyReservations.jsx';
import ReservationDetail from './pages/ReservationDetail.jsx';
import MyTickets from './pages/MyTickets.jsx';
import MyPage from './pages/MyPage.jsx';
import OAuthCallback from './pages/OAuthCallback.jsx';
import AdminSlots from './pages/admin/AdminSlots.jsx';
import AdminAttractionForm from './pages/admin/AdminAttractionForm.jsx';
import AdminReservations from './pages/admin/AdminReservations.jsx';
import AdminMembers from './pages/admin/AdminMembers.jsx';
import AdminOrders from './pages/admin/AdminOrders.jsx';
import AdminProducts from './pages/admin/AdminProducts.jsx';
import AdminTicketEntry from './pages/admin/AdminTicketEntry.jsx';

// 주소(URL)와 화면(페이지)을 짝지어 주는 곳입니다.
// 예) 브라우저에서 /attractions 로 가면 <Attractions /> 화면이 보여요.
export default function App() {
  return (
    <DeviceFrame>
      <Routes>
        {/* 사용자 화면 */}
        <Route path="/" element={<Home />} />
        <Route path="/login" element={<Login />} />
        {/* 소셜 로그인을 마치고 백엔드가 돌려보내 주는 곳 */}
        <Route path="/oauth/callback" element={<OAuthCallback />} />
        <Route path="/attractions" element={<Attractions />} />
        {/* /attractions/:id 보다 위에 둬야 "ask" 가 id 로 읽히지 않아요 */}
        <Route path="/attractions/ask" element={<AttractionQuestion />} />
        <Route path="/attractions/:id" element={<AttractionDetail />} />
        <Route path="/tickets" element={<Tickets />} />
        <Route path="/checkout/:orderId" element={<Checkout />} />
        <Route path="/checkout/:orderId/complete" element={<PaymentComplete />} />
        <Route path="/route" element={<RouteSetup />} />
        <Route path="/route/result" element={<RouteResult />} />
        <Route path="/my/reservations" element={<MyReservations />} />
        <Route path="/my/reservations/:id" element={<ReservationDetail />} />
        <Route path="/my/tickets" element={<MyTickets />} />
        <Route path="/my" element={<MyPage />} />

        {/* 관리자 화면: RequireAdmin(문지기)이 먼저 검사하고, 관리자일 때만 안쪽 화면을 보여 줘요 */}
        <Route element={<RequireAdmin />}>
          <Route path="/admin" element={<Navigate to="/admin/slots" replace />} />
          <Route path="/admin/slots" element={<AdminSlots />} />
          <Route path="/admin/attractions/new" element={<AdminAttractionForm />} />
          <Route path="/admin/attractions/:id/edit" element={<AdminAttractionForm />} />
          <Route path="/admin/reservations" element={<AdminReservations />} />
          <Route path="/admin/members" element={<AdminMembers />} />
          <Route path="/admin/orders" element={<AdminOrders />} />
          <Route path="/admin/products" element={<AdminProducts />} />
          <Route path="/admin/entry" element={<AdminTicketEntry />} />
        </Route>

        {/* 없는 주소로 오면 홈으로 */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </DeviceFrame>
  );
}
