import { Check, Monitor, Moon, Sun } from 'lucide-react';
import { themeStore, useTheme } from './theme';
import { LayoutPreferences } from './SidebarLayout';
import './appearance.css';

export default function Appearance() {
  const theme = useTheme();
  const current = themeStore.themes.find(item => item.id === theme.resolved)!;
  return <><section className="appearance-panel" aria-labelledby="appearance-title">
    <div className="appearance-heading"><div><h2 id="appearance-title">工作台外观</h2><p>选一个适合你的主题。选择会应用到当前客户端的全部项目和页面。</p></div><span className="small-tag">{theme.scheme === 'dark' ? <Moon size={13} /> : <Sun size={13} />}{theme.scheme === 'dark' ? '深色' : '浅色'}</span></div>
    <div className="appearance-choices" role="group" aria-label="主题选择">
      <button type="button" className="appearance-system" aria-pressed={theme.choice === 'system'} onClick={() => themeStore.setChoice('system')}><Monitor size={21} /><span><strong>跟随系统</strong><small>系统浅色使用青瓷，系统深色使用墨夜。</small></span>{theme.choice === 'system' && <Check size={18} aria-hidden="true" />}</button>
      <div className="theme-grid">{themeStore.themes.map(item => <button type="button" key={item.id} className="theme-choice" aria-label={item.name} aria-pressed={theme.choice === item.id} onClick={() => themeStore.setChoice(item.id)}>
        <span className="theme-preview" data-theme={item.id} aria-hidden="true"><span className="theme-preview-sidebar"><i /><i /><i /></span><span className="theme-preview-page"><span className="theme-preview-title" /><span className="theme-preview-card"><i /><i /><i /></span><span className="theme-preview-action" /></span></span>
        <span className="theme-choice-heading"><strong>{item.name}</strong><span>{item.scheme === 'dark' ? '深色' : '浅色'}</span>{theme.choice === item.id && <Check size={17} aria-hidden="true" />}</span><span className="theme-choice-description">{item.description}</span>
      </button>)}</div>
    </div>
    <p className="theme-status" role="status">当前外观：{theme.choice === 'system' ? `跟随系统${theme.systemScheme === 'dark' ? '深色' : '浅色'} · ` : ''}{current.name}。{theme.storageAvailable ? '已保存在此客户端，重新打开后继续使用。' : '此客户端无法保存偏好，本次窗口仍可切换主题。'}</p>
  </section><LayoutPreferences /></>;
}
