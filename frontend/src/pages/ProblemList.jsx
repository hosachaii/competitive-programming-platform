import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import client from '../api/client';

export default function ProblemList() {
  const [problems, setProblems] = useState([]);
  const [difficulty, setDifficulty] = useState('');

  useEffect(() => {
    const params = difficulty ? { difficulty } : {};
    client.get('/problems', { params }).then(res => setProblems(res.data.content));
  }, [difficulty]);

  return (
    <div>
      <h2>Problems</h2>
      <select value={difficulty} onChange={e => setDifficulty(e.target.value)} style={{ marginBottom: '1rem' }}>
        <option value="">All difficulties</option>
        <option value="EASY">Easy</option>
        <option value="MEDIUM">Medium</option>
        <option value="HARD">Hard</option>
      </select>
      <div className="card">
        <table>
          <thead><tr><th>Title</th><th>Difficulty</th><th>Tags</th></tr></thead>
          <tbody>
            {problems.map(p => (
              <tr key={p.id}>
                <td><Link to={`/problems/${p.slug}`}>{p.title}</Link></td>
                <td><span className={`badge ${p.difficulty}`}>{p.difficulty}</span></td>
                <td>{p.tags?.join(', ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
