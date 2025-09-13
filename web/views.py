from flask import render_template, request, jsonify, flash, redirect, url_for
from sqlalchemy import desc, func
from datetime import datetime, timezone, timedelta
from models import db, Message, Device
from . import web
import requests
import json

@web.route('/')
@web.route('/dashboard')
def dashboard():
    """Dashboard showing message overview and statistics - mirrors CLI status command"""
    try:
        # Get total message count
        total_messages = db.session.query(func.count(Message.id)).scalar() or 0
        
        # Get unread message count
        unread_count = db.session.query(func.count(Message.id)).filter(
            Message.is_read == False
        ).scalar() or 0
        
        # Get recent messages (last 24 hours)
        last_24h = datetime.now(timezone.utc) - timedelta(hours=24)
        recent_count = db.session.query(func.count(Message.id)).filter(
            Message.timestamp >= last_24h
        ).scalar() or 0
        
        # Get messages by type
        type_stats = db.session.query(
            Message.type,
            func.count(Message.id).label('count')
        ).group_by(Message.type).all()
        
        # Get messages by device (mirrors CLI device stats)
        device_stats = db.session.query(
            Message.source_device_id,
            func.count(Message.id).label('count')
        ).group_by(Message.source_device_id).all()
        
        # Get latest message timestamp
        latest_message = db.session.query(Message).order_by(desc(Message.timestamp)).first()
        latest_timestamp = latest_message.timestamp if latest_message else None
        
        # Get recent messages for preview (mirrors CLI messages --limit 5)
        recent_messages = db.session.query(Message).order_by(
            desc(Message.timestamp)
        ).limit(5).all()
        
        stats = {
            'total_messages': total_messages,
            'unread_count': unread_count,
            'recent_count': recent_count,
            'latest_timestamp': latest_timestamp,
            'type_stats': {stat.type: stat.count for stat in type_stats},
            'device_stats': {stat.source_device_id: stat.count for stat in device_stats}
        }
        
        return render_template('dashboard.html', 
                             stats=stats, 
                             recent_messages=recent_messages)
    
    except Exception as e:
        flash(f'Error loading dashboard: {str(e)}', 'error')
        return render_template('dashboard.html', stats={}, recent_messages=[])

@web.route('/messages')
def messages():
    """List messages with filtering - mirrors CLI messages command"""
    # Get filter parameters (same as CLI)
    page = int(request.args.get('page', 1))
    per_page = int(request.args.get('limit', 20))
    message_type = request.args.get('type', '').strip()
    device = request.args.get('device', '').strip()
    unread_only = request.args.get('unread') == 'on'
    
    try:
        # Build query
        query = db.session.query(Message)
        
        # Apply filters (same logic as CLI)
        if message_type:
            query = query.filter(Message.type == message_type)
        if device:
            query = query.filter(Message.source_device_id == device)
        if unread_only:
            query = query.filter(Message.is_read == False)
        
        # Order by timestamp (newest first)
        query = query.order_by(desc(Message.timestamp))
        
        # Paginate
        pagination = query.paginate(
            page=page, 
            per_page=per_page, 
            error_out=False
        )
        
        messages = pagination.items
        
        # Get filter options
        message_types = db.session.query(Message.type).distinct().all()
        message_types = [t[0] for t in message_types if t[0]]
        
        devices = db.session.query(Message.source_device_id).distinct().all()
        devices = [d[0] for d in devices if d[0]]
        
        return render_template('messages.html',
                             messages=messages,
                             pagination=pagination,
                             message_types=message_types,
                             devices=devices,
                             current_filters={
                                 'type': message_type,
                                 'device': device,
                                 'unread': unread_only,
                                 'limit': per_page
                             })
    
    except Exception as e:
        flash(f'Error loading messages: {str(e)}', 'error')
        return render_template('messages.html',
                             messages=[],
                             pagination=None,
                             message_types=[],
                             devices=[],
                             current_filters={})

@web.route('/messages/<message_id>')
def message_detail(message_id):
    """Show detailed message view"""
    try:
        message = db.session.query(Message).filter(Message.id == message_id).first()
        if not message:
            flash('Message not found', 'error')
            return redirect(url_for('web.messages'))
        
        return render_template('message_detail.html', message=message)
    
    except Exception as e:
        flash(f'Error loading message: {str(e)}', 'error')
        return redirect(url_for('web.messages'))

@web.route('/messages/<message_id>/read', methods=['POST'])
def mark_read(message_id):
    """Mark message as read - mirrors CLI mark-read command"""
    try:
        message = db.session.query(Message).filter(Message.id == message_id).first()
        if not message:
            return jsonify({'error': 'Message not found'}), 404
        
        message.is_read = True
        db.session.commit()
        
        # Return JSON for AJAX requests, redirect for form submissions
        if request.is_json or request.headers.get('Accept', '').startswith('application/json'):
            return jsonify({'success': True, 'message': 'Message marked as read'})
        else:
            flash('Message marked as read', 'success')
            return redirect(request.referrer or url_for('web.messages'))
    
    except Exception as e:
        if request.is_json or request.headers.get('Accept', '').startswith('application/json'):
            return jsonify({'error': str(e)}), 500
        else:
            flash(f'Error marking message as read: {str(e)}', 'error')
            return redirect(request.referrer or url_for('web.messages'))

@web.route('/status')
def status():
    """Show server status and statistics - mirrors CLI status command"""
    try:
        # Get sync status (same as CLI)
        total_messages = db.session.query(func.count(Message.id)).scalar() or 0
        latest_message = db.session.query(Message).order_by(desc(Message.timestamp)).first()
        latest_timestamp = latest_message.timestamp if latest_message else None
        
        # Get device stats (mirrors CLI status device stats)
        device_stats = db.session.query(
            Message.source_device_id,
            func.count(Message.id).label('count')
        ).group_by(Message.source_device_id).all()
        
        # Get message type distribution
        type_stats = db.session.query(
            Message.type,
            func.count(Message.id).label('count')
        ).group_by(Message.type).all()
        
        # Get recent activity (last 7 days)
        activity_data = []
        for i in range(7):
            day = datetime.now(timezone.utc) - timedelta(days=i)
            day_start = day.replace(hour=0, minute=0, second=0, microsecond=0)
            day_end = day_start + timedelta(days=1)
            
            count = db.session.query(func.count(Message.id)).filter(
                Message.timestamp >= day_start,
                Message.timestamp < day_end
            ).scalar() or 0
            
            activity_data.append({
                'date': day_start.strftime('%Y-%m-%d'),
                'count': count
            })
        
        activity_data.reverse()  # Oldest first for chart
        
        status_data = {
            'healthy': True,
            'total_messages': total_messages,
            'latest_timestamp': latest_timestamp,
            'device_stats': {stat.source_device_id: stat.count for stat in device_stats},
            'type_stats': {stat.type: stat.count for stat in type_stats},
            'activity_data': activity_data
        }
        
        return render_template('status.html', status=status_data)
    
    except Exception as e:
        flash(f'Error loading status: {str(e)}', 'error')
        return render_template('status.html', status={'healthy': False})

@web.route('/api/messages/<message_id>/read', methods=['POST'])
def api_mark_read(message_id):
    """API endpoint for marking messages as read (for JavaScript)"""
    return mark_read(message_id)