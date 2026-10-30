import { useEffect, useState } from 'react';
import client from '../api/client';

export default function Leaderboard() {
  const [entries, setEntries] = useState([]);

  useEffect(() => {
    client.get('/leaderboard').then(res => setEntries(res.data));
  }, []);

  return (
    <div>
      <h2>Leaderboard</h2>
      <div className="card">
        <table>
          <thead><tr><th>#</th><th>User</th><th>Solved</th></tr></thead>
          <tbody>
            {entries.map((e, i) => (
              <tr key={e.userId}>
                <td>{i + 1}</td>
                <td>{e.username}</td>
                <td>{e.solvedCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
