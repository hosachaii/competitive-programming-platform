import { createContext, useContext, useState } from 'react';
import client from '../api/client';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    const stored = localStorage.getItem('user');
    return stored ? JSON.parse(stored) : null;
  });

  const login = async (email, password) => {
    const { data } = await client.post('/auth/login', { email, password });
    persist(data);
  };

  const register = async (email, username, password) => {
    const { data } = await client.post('/auth/register', { email, username, password });
    persist(data);
  };

  const logout = () => {
    localStorage.clear();
    setUser(null);
  };

  const persist = (data) => {
    localStorage.setItem('accessToken', data.accessToken);
    localStorage.setItem('refreshToken', data.refreshToken);
    const u = { id: data.userId, username: data.username, role: data.role };
    localStorage.setItem('user', JSON.stringify(u));
    setUser(u);
  };

  return (
    <AuthContext.Provider value={{ user, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
