import { Routes, Route, Link, Navigate } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import Login from './pages/Login';
import Register from './pages/Register';
import ProblemList from './pages/ProblemList';
import ProblemDetail from './pages/ProblemDetail';
import SubmissionStatus from './pages/SubmissionStatus';
import Leaderboard from './pages/Leaderboard';
import AdminNewProblem from './pages/AdminNewProblem';
import AdminEditProblem from './pages/AdminEditProblem';

function RequireAuth({ children }) {
  const { user } = useAuth();
  return user ? children : <Navigate to="/login" replace />;
}

function RequireAdmin({ children }) {
  const { user } = useAuth();
  return user?.role === 'ADMIN' ? children : <Navigate to="/" replace />;
}

export default function App() {
  const { user, logout } = useAuth();

  return (
    <div>
      <nav>
        <Link to="/">Problems</Link>
        <Link to="/leaderboard">Leaderboard</Link>
        {user?.role === 'ADMIN' && <Link to="/admin/new-problem">+ New Problem</Link>}
        <div className="spacer" />
        {user ? (
          <>
            <span>{user.username}</span>
            <button className="secondary" onClick={logout}>Logout</button>
          </>
        ) : (
          <>
            <Link to="/login">Login</Link>
            <Link to="/register">Register</Link>
          </>
        )}
      </nav>
      <div className="container">
        <Routes>
          <Route path="/" element={<ProblemList />} />
          <Route path="/problems/:slug" element={<ProblemDetail />} />
          <Route path="/submissions/:id" element={<RequireAuth><SubmissionStatus /></RequireAuth>} />
          <Route path="/leaderboard" element={<Leaderboard />} />
          <Route path="/admin/new-problem" element={<RequireAdmin><AdminNewProblem /></RequireAdmin>} />
          <Route path="/admin/edit-problem/:id" element={<RequireAdmin><AdminEditProblem /></RequireAdmin>} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
        </Routes>
      </div>
    </div>
  );
}
